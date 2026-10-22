"""Offline checks for the release contract; never contact GitHub."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import release


class ReleaseTests(unittest.TestCase):
    def test_event_modes(self):
        for event, requested, expected in [('push', False, 'nightly'), ('push', True, 'nightly'),
                                           ('workflow_dispatch', False, 'nightly'), ('workflow_dispatch', True, 'release'),
                                           ('pull_request', False, 'build'), ('pull_request', True, 'build')]:
            with self.subTest(event=event, requested=requested):
                self.assertEqual(release.select_mode(event, requested), expected)
        with self.assertRaises(ValueError):
            release.select_mode('pull_request_target', True)

    def test_prepare_exports_actual_mode_and_version(self):
        for event, requested, mode, version in [('push', 'false', 'nightly', '0.7.0-nightly.42'),
                ('workflow_dispatch', 'false', 'nightly', '0.7.0-nightly.42'),
                ('workflow_dispatch', 'true', 'release', '0.7.0'), ('pull_request', 'true', 'build', '0.7.0')]:
            with self.subTest(event=event, requested=requested), tempfile.TemporaryDirectory() as directory:
                previous = Path.cwd()
                try:
                    os.chdir(directory)
                    Path('gradle.properties').write_text('mod_version=0.7.0\n')
                    env = dict(GITHUB_EVENT_NAME=event, RELEASE_REQUESTED=requested, RELEASE_VERSION='', GITHUB_RUN_NUMBER='42',
                               GITHUB_ENV=str(Path(directory, 'env')), GITHUB_OUTPUT=str(Path(directory, 'output')))
                    with patch.dict(os.environ, env):
                        release.prepare()
                    self.assertEqual(Path('env').read_text(), 'RELEASE_MODE=' + mode + '\nBUILD_VERSION=' + version + '\n')
                    self.assertEqual(Path('output').read_text(), 'mode=' + mode + '\nversion=' + version + '\n')
                finally:
                    os.chdir(previous)

    def test_blank_version_uses_project(self):
        self.assertEqual(release.select_version('release', '  ', '0.7.0', ''), '0.7.0')

    def test_override(self):
        self.assertEqual(release.select_version('release', ' 1.2.3 ', '0.7.0', ''), '1.2.3')

    def test_unstable_and_unsafe_release_versions_are_rejected(self):
        for version in ['1.0.0-rc.1', '1.0.0-nightly.1', '1.0.0+build.1', 'v1.0.0',
                        '01.0.0', '1.0', '1.0.0\nEVIL=true', '$(echo bad)']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.select_version('release', version, '1.0.0', '')

    def test_nightly_is_distinct(self):
        self.assertEqual(release.select_version('nightly', '', '1.0.0', '42'), '1.0.0-nightly.42')

    def test_regular_release_plan(self):
        with patch.dict(os.environ, RELEASE_MODE='release', BUILD_VERSION='0.7.0', RELEASE_NAME='AOP: UI', GITHUB_SHA='abc'):
            self.assertEqual(release.release_plan(), dict(tag='v0.7.0', prerelease=False, draft=False,
                                                         title='AOP: UI 0.7.0', latest=True, target='abc'))

    def test_build_cannot_publish(self):
        with patch.dict(os.environ, RELEASE_MODE='build'), self.assertRaises(ValueError):
            release.release_plan()

    def test_nightly_plan(self):
        with patch.dict(os.environ, RELEASE_MODE='nightly', BUILD_VERSION='1.0.0-nightly.42', GITHUB_SHA='abc'):
            self.assertTrue(release.release_plan()['prerelease'])
            self.assertFalse(release.release_plan()['latest'])

    def test_nightly_replaces_only_nightly_without_commit_list(self):
        from subprocess import CompletedProcess
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                Path('dist').mkdir()
                Path('dist/mod-1.0.0-nightly.42.jar').write_bytes(b'test')
                Path('gradle.properties').write_text('minecraft_version=1.21.1\n')
                env = dict(RELEASE_MODE='nightly', BUILD_VERSION='1.0.0-nightly.42', RELEASE_NAME='Test',
                           RELEASE_REQUIREMENTS='Java 21.', GITHUB_SHA='abc', GH_REPO='owner/repo', RUNNER_TEMP=directory)
                responses = [CompletedProcess([], 0, stdout='{"id":123}'), CompletedProcess([], 0, stdout='{"ref":"refs/tags/nightly"}'),
                             CompletedProcess([], 0, stdout=''), CompletedProcess([], 0, stdout=''), CompletedProcess([], 0)]
                with patch.dict(os.environ, env), patch('release.subprocess.run', side_effect=responses) as run, patch('release.commit_notes') as notes:
                    release.publish()
                    deleted = [call.args[0][-1] for call in run.call_args_list if 'DELETE' in call.args[0]]
                    self.assertEqual(deleted, ['repos/owner/repo/releases/123', 'repos/owner/repo/git/refs/tags/nightly'])
                    command = run.call_args.args[0]
                    self.assertEqual(command[:4], ['gh', 'release', 'create', 'nightly'])
                    self.assertIn('--prerelease=true', command)
                    self.assertIn('--latest=false', command)
                    notes.assert_not_called()
                    self.assertNotIn('## Commits', Path(directory, 'aop-release-notes.md').read_text())
            finally:
                os.chdir(previous)

    def test_publish_flags_and_existing_version(self):
        with tempfile.TemporaryDirectory() as directory:
            previous = Path.cwd()
            try:
                os.chdir(directory)
                Path('dist').mkdir()
                Path('dist/mod-1.0.0.jar').write_bytes(b'test')
                Path('gradle.properties').write_text('minecraft_version=1.21.1\n')
                env = dict(RELEASE_MODE='release', BUILD_VERSION='1.0.0', RELEASE_NAME='Test',
                           RELEASE_REQUIREMENTS='Java 21.', GITHUB_SHA='abc', GH_REPO='owner/repo', RUNNER_TEMP=directory)
                with patch.dict(os.environ, env), patch('release.subprocess.run') as run, patch('release.commit_notes', return_value='## Commits\n- abc fix\n') as notes:
                    run.return_value.returncode = 1
                    run.return_value.stderr = '(HTTP 404)'
                    release.publish()
                    command = run.call_args.args[0]
                    for flag in ['--prerelease=false', '--draft=false', '--latest=true']:
                        self.assertIn(flag, command)
                    self.assertEqual(command[:4], ['gh', 'release', 'create', 'v1.0.0'])
                    self.assertIn('## Commits', Path(directory, 'aop-release-notes.md').read_text())
                    notes.assert_called_once_with('owner/repo', 'abc')
                    run.reset_mock()
                    run.return_value.returncode = 0
                    run.return_value.stdout = json.dumps({'id': 123})
                    with self.assertRaisesRegex(ValueError, 'already exists'):
                        release.publish()
                    self.assertTrue(all(call.args[0][1] == 'api' for call in run.call_args_list))
                    self.assertFalse(any('DELETE' in call.args[0] for call in run.call_args_list))
                    env.update(RELEASE_MODE='nightly', BUILD_VERSION='1.0.0-nightly.42')
                    notes.reset_mock()
                    run.return_value.returncode = 1
                    with patch.dict(os.environ, env):
                        release.publish()
                    notes.assert_not_called()
                    self.assertNotIn('## Commits', Path(directory, 'aop-release-notes.md').read_text())
            finally:
                os.chdir(previous)

    def test_commit_range_uses_stable_ancestor(self):
        from subprocess import CompletedProcess
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            if command[0] == 'gh':
                self.assertIn('.[] | select(.draft == false and .prerelease == false) | .tag_name', command)
                output = 'v0.9.0\nv0.8.0\n'
            elif command[1] == 'rev-parse':
                output = 'aaa' if 'v0.9.0' in command[-1] else 'bbb'
            elif command[1] == 'rev-list':
                output = '2' if command[-1].startswith('aaa') else '4'
            elif command[1] == 'log':
                self.assertEqual(command[-1], 'aaa..ccc')
                output = '111111111\tfix first\n222222222\tfix second\n'
            else:
                output = ''
            return CompletedProcess(command, 0, stdout=output, stderr='')
        with patch('release.subprocess.run', side_effect=run):
            result = release.commit_notes('owner/repo', 'ccc')
        self.assertIn('/commit/111111111', result)
        self.assertIn('/commit/222222222', result)
        self.assertIn('/compare/v0.9.0...ccc', result)

    def test_first_release_includes_full_history(self):
        from subprocess import CompletedProcess
        with patch('release.subprocess.run', side_effect=[CompletedProcess([], 0, stdout=''),
                   CompletedProcess([], 0, stdout='123456789\tinitial\n')]) as run:
            result = release.commit_notes('owner/repo', 'abc')
            self.assertEqual(run.call_args.args[0][-1], 'abc')
        self.assertIn('initial', result)
        self.assertNotIn('Full comparison', result)

    def test_collection_validates_version_and_metadata(self):
        for metadata_version, leaked in [('1.0.0', False), ('0.9.0', False), ('1.0.0', True)]:
            with self.subTest(metadata_version=metadata_version, leaked=leaked), tempfile.TemporaryDirectory() as directory:
                previous = Path.cwd()
                try:
                    os.chdir(directory)
                    with zipfile.ZipFile('mod-1.0.0.jar', 'w') as jar:
                        jar.writestr('META-INF/neoforge.mods.toml', 'version="' + metadata_version + '"\n')
                        if leaked:
                            jar.writestr('mod/verification/Test.class', b'bad')
                    with patch.dict(os.environ, BUILD_VERSION='1.0.0', RELEASE_ARTIFACTS='mod-*.jar'):
                        if metadata_version != '1.0.0' or leaked:
                            with self.assertRaises(ValueError):
                                release.collect()
                        else:
                            release.collect()
                            self.assertTrue(Path('dist/SHA256SUMS.txt').is_file())
                finally:
                    os.chdir(previous)


if __name__ == '__main__':
    unittest.main()
