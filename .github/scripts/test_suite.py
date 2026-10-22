"""Offline tests for artifact compatibility, channel isolation and publication."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import suite


class SuiteTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.directory.name)
        self.cache = patch.object(suite, 'CACHE', Path('cache'))
        self.cache.start()
        self.env = patch.dict(os.environ, {'ACT': 'false', 'SUITE_FORCE_REBUILD': 'false'}, clear=True)
        self.env.start()
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.lock('release')

    def tearDown(self):
        self.output.__exit__(None, None, None)
        self.env.stop()
        self.cache.stop()
        os.chdir(self.previous)
        self.directory.cleanup()

    def lock(self, mode):
        suite.write_json(suite.CACHE / 'lock.json', dict(format=1, mode=mode, recipe='recipe', runtime={'java': '21'},
            repositories=suite.REPOSITORIES, sources={stage: dict(version='1.0.0', revision=(str(index + 1) * 40), content=stage)
                                                      for index, stage in enumerate(suite.STAGES)}))

    def jars(self, stage, version, folder):
        prefix = {'ui': 'aopui-1.21.1-', 'core': 'thaumcraft-1.21.1-',
                  'tinkerer': 'ThaumicTinkerer-1.21.1-', 'compact': 'aoptc4-compact-'}[stage]
        names = [prefix + version + '.jar', prefix + version + '-sources.jar']
        if stage == 'core':
            names += ['thaumcraft-api-' + version + '.jar', 'thaumcraft-api-' + version + '-sources.jar']
        folder.mkdir(parents=True, exist_ok=True)
        for name in names:
            with zipfile.ZipFile(folder / name, 'w') as jar:
                jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="' + suite.MOD_IDS[stage] + '"\nversion="' + version + '"\n')
        return {name: suite.digest((folder / name).read_bytes()) for name in names}

    def seed(self, stage, channel):
        suite.plan(stage)
        expected = suite.read_json(suite.CACHE / 'plans' / (stage + '-' + channel + '.json'))
        folder = suite.CACHE / stage / channel
        manifest = dict(key=expected['key'], plan=expected, artifacts=self.jars(stage, expected['version'], folder))
        suite.write_json(folder / 'manifest.json', manifest)
        suite.write_json(suite.CACHE / 'selected' / (stage + '.json'), dict(channel=channel))
        return manifest

    def seed_all(self):
        for stage in suite.STAGES:
            self.seed(stage, 'release')

    def test_exact_release_artifacts_skip_gradle(self):
        self.seed('ui', 'release')
        with patch('suite.subprocess.run') as run:
            suite.build('ui')
        run.assert_not_called()

    def test_standalone_ui_needs_no_other_sources(self):
        with patch.dict(os.environ, SUITE_TARGET='ui'), patch('suite.source_identity', return_value=dict(
                version='0.7.0', revision='a' * 40, content='ui')) as source, patch('suite.subprocess.run') as java:
            java.return_value.stdout, java.return_value.stderr = '', 'Java 21'
            suite.prepare()
        source.assert_called_once_with(Path('.'))
        self.assertEqual(set(suite.read_json(suite.CACHE / 'lock.json')['sources']), {'ui'})

    def test_standalone_roots_and_dependency_order(self):
        for target in suite.STAGES:
            with patch.dict(os.environ, SUITE_TARGET=target):
                self.assertEqual(suite.stages(), (*suite.DEPS[target], target))
                self.assertEqual(suite.source_root(target), Path('.'))
                if target in ('tinkerer', 'compact'):
                    self.assertEqual(suite.source_root('core'), Path('.dependencies/thaumcraft'))
        with patch.dict(os.environ, SUITE_TARGET='unknown'):
            with self.assertRaisesRegex(ValueError, 'Invalid standalone target'):
                suite.stages()

    def test_standalone_and_suite_have_identical_artifact_plans(self):
        self.seed_all()
        expected = {stage: suite.plan(stage) for stage in suite.STAGES}
        for target in suite.STAGES:
            with patch.dict(os.environ, SUITE_TARGET=target):
                self.assertEqual(suite.plan(target), expected[target])

    def test_unrelated_sources_do_not_invalidate_a_component(self):
        first = suite.plan('ui')
        lock = suite.read_json(suite.CACHE / 'lock.json')
        lock['sources']['compact']['content'] = 'unrelated change'
        suite.write_json(suite.CACHE / 'lock.json', lock)
        self.assertEqual(suite.plan('ui'), first)

    def test_standalone_collects_only_its_own_jars(self):
        self.seed_all()
        suite.collect('tinkerer')
        self.assertEqual(set(suite.read_json(Path('dist/suite/manifest.json'))['stages']), {'tinkerer'})
        self.assertEqual(len(list(Path('dist/suite').glob('*.jar'))), 2)

    def test_standalone_publication_never_publishes_dependencies(self):
        self.seed_all()
        with patch('suite.api') as api, contextlib.redirect_stdout(io.StringIO()) as output:
            suite.publish(dry_run=True, stage='compact')
        api.assert_not_called()
        self.assertEqual(set(json.loads(output.getvalue())), {'compact'})

    def test_release_plan_rejects_nightly_dependency(self):
        self.seed('ui', 'nightly')
        with self.assertRaisesRegex(ValueError, 'cannot use nightly'):
            suite.plan('core')

    def test_nightly_can_reuse_release_and_prefers_it(self):
        self.lock('nightly')
        self.seed('ui', 'release')
        self.seed('ui', 'nightly')
        with patch('suite.subprocess.run') as run:
            suite.build('ui')
        run.assert_not_called()
        self.assertEqual(suite.verified('ui')['plan']['channel'], 'release')

    def test_release_never_uses_nightly_jars(self):
        self.seed('ui', 'nightly')
        def gradle(command, **kwargs):
            self.assertIn('-Pmod_version=1.0.0', command)
            self.jars('ui', '1.0.0', Path(suite.ROOTS['ui']) / 'build/libs')
        with patch('suite.subprocess.run', side_effect=gradle) as run:
            suite.build('ui')
        self.assertEqual(run.call_count, 1)
        self.assertEqual(suite.verified('ui')['plan']['channel'], 'release')

    def test_release_rejects_selected_nightly_even_if_checksum_valid(self):
        self.seed('ui', 'nightly')
        with self.assertRaisesRegex(ValueError, 'cannot use nightly'):
            suite.verified('ui')

    def test_checksum_corruption_is_rebuilt(self):
        manifest = self.seed('ui', 'release')
        name = next(iter(manifest['artifacts']))
        (suite.CACHE / 'ui/release' / name).write_bytes(b'corrupt')
        def gradle(*args, **kwargs):
            self.jars('ui', '1.0.0', Path(suite.ROOTS['ui']) / 'build/libs')
        with patch('suite.subprocess.run', side_effect=gradle) as run:
            suite.build('ui')
        self.assertEqual(run.call_count, 1)
        suite.verified('ui')

    def test_dependency_bytes_invalidate_downstream_cache(self):
        manifest = self.seed('ui', 'release')
        first = suite.plan('core')['release-key']
        name = next(iter(manifest['artifacts']))
        path = suite.CACHE / 'ui/release' / name
        with zipfile.ZipFile(path, 'a') as jar:
            jar.writestr('changed.class', b'new API implementation')
        manifest['artifacts'][name] = suite.digest(path.read_bytes())
        suite.write_json(suite.CACHE / 'ui/release/manifest.json', manifest)
        self.assertNotEqual(first, suite.plan('core')['release-key'])

    def test_source_changes_invalidate_own_cache(self):
        first = suite.plan('ui')['release-key']
        lock = suite.read_json(suite.CACHE / 'lock.json')
        lock['sources']['ui']['content'] = 'changed'
        suite.write_json(suite.CACHE / 'lock.json', lock)
        self.assertNotEqual(first, suite.plan('ui')['release-key'])

    def test_toolchain_changes_invalidate_cache(self):
        first = suite.plan('ui')['release-key']
        lock = suite.read_json(suite.CACHE / 'lock.json')
        lock['runtime']['java'] = '21.0.99'
        suite.write_json(suite.CACHE / 'lock.json', lock)
        self.assertNotEqual(first, suite.plan('ui')['release-key'])

    def test_release_fetch_never_queries_nightly(self):
        suite.plan('ui')
        with patch('suite.api', return_value=None) as api:
            suite.fetch('ui')
        api.assert_called_once_with('repos/Salossov/aop-ui/releases/tags/v1.0.0', missing_ok=True)

    def test_published_release_import_requires_matching_manifest_and_checksums(self):
        manifest = self.seed('ui', 'release')
        origin = Path('remote')
        shutil.copytree(suite.CACHE / 'ui/release', origin)
        shutil.rmtree(suite.CACHE / 'ui/release')
        names = ['aop-suite-manifest.json', *manifest['artifacts']]
        remote = dict(draft=False, prerelease=False, assets=[dict(id=i, name=name) for i, name in enumerate(names)])
        def download(repository, asset, path):
            source = 'manifest.json' if asset['name'] == 'aop-suite-manifest.json' else asset['name']
            shutil.copyfile(origin / source, path)
        with patch('suite.api', return_value=remote), patch('suite.download_asset', side_effect=download):
            suite.fetch('ui')
        self.assertEqual(suite.verified_candidate('ui', 'release'), manifest)

    def test_different_published_source_is_not_imported(self):
        manifest = self.seed('ui', 'release')
        shutil.rmtree(suite.CACHE / 'ui/release')
        manifest['plan']['source']['revision'] = 'a' * 40
        remote = dict(draft=False, prerelease=False, assets=[dict(id=1, name='aop-suite-manifest.json')])
        def download(repository, asset, path):
            suite.write_json(path, manifest)
        with patch('suite.api', return_value=remote), patch('suite.download_asset', side_effect=download) as download:
            suite.fetch('ui')
        self.assertEqual(download.call_count, 1)
        self.assertFalse((suite.CACHE / 'ui/release/manifest.json').exists())

    def test_stable_collision_aborts_before_any_publication(self):
        self.seed_all()
        with patch('suite.api', return_value=dict(prerelease=False, draft=False, assets=[])), patch('suite.release.publish') as publish:
            with self.assertRaisesRegex(ValueError, 'cannot be verified or overwritten'):
                suite.publish()
        publish.assert_not_called()

    def test_publication_dry_run_contacts_no_repositories(self):
        self.seed_all()
        with patch('suite.api') as api, contextlib.redirect_stdout(io.StringIO()) as output:
            suite.publish(dry_run=True)
        api.assert_not_called()
        plans = json.loads(output.getvalue())
        self.assertEqual(set(plans), set(suite.STAGES))
        self.assertTrue(all(not plan['prerelease'] for plan in plans.values()))

    def test_collection_verifies_all_artifacts_again(self):
        self.seed_all()
        suite.collect()
        self.assertEqual(len(list(Path('dist/suite').glob('*.jar'))), 10)
        self.assertEqual(len(Path('dist/suite/SHA256SUMS.txt').read_text().splitlines()), 10)
        self.assertEqual(set(suite.read_json(Path('dist/suite/manifest.json'))['stages']), set(suite.STAGES))

    def test_bad_metadata_is_rejected_even_with_matching_checksum(self):
        manifest = self.seed('ui', 'release')
        name = next(name for name in manifest['artifacts'] if not name.endswith('-sources.jar'))
        path = suite.CACHE / 'ui/release' / name
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="aop_ui"\nversion="0.1.0"\n')
        manifest['artifacts'][name] = suite.digest(path.read_bytes())
        suite.write_json(suite.CACHE / 'ui/release/manifest.json', manifest)
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            suite.verified('ui')

    def test_sources_only_cache_is_not_a_complete_mod(self):
        manifest = self.seed('ui', 'release')
        name = next(name for name in manifest['artifacts'] if not name.endswith('-sources.jar'))
        checksum = manifest['artifacts'].pop(name)
        manifest['artifacts']['other-1.0.0-sources.jar'] = checksum
        suite.write_json(suite.CACHE / 'ui/release/manifest.json', manifest)
        with self.assertRaisesRegex(ValueError, 'Incomplete artifact set'):
            suite.verified('ui')

    def test_existing_identical_releases_are_not_republished(self):
        self.seed_all()
        def api(path, **kwargs):
            repository = '/'.join(path.split('/')[1:3])
            stage = next(stage for stage, repo in suite.REPOSITORIES.items() if repo == repository)
            manifest = suite.verified(stage)
            return dict(prerelease=False, draft=False, assets=[dict(name='aop-suite-manifest.json', id=stage),
                *[dict(name=name, digest='sha256:' + checksum) for name, checksum in manifest['artifacts'].items()]])
        def download(repository, asset, path):
            suite.write_json(path, suite.verified(asset['id']))
        with patch('suite.api', side_effect=api), patch('suite.download_asset', side_effect=download), patch('suite.release.publish') as publish:
            suite.publish()
        publish.assert_not_called()

    def test_act_source_inventory_hashes_actual_file_contents(self):
        root = Path('source')
        root.mkdir()
        (root / 'gradle.properties').write_text('mod_version=1.0.0\n')
        suite.write_json(root / '.suite-source.json', dict(revision='1' * 40, files=['gradle.properties']))
        with patch.dict(os.environ, ACT='true'):
            first = suite.source_identity(root)
            (root / 'gradle.properties').write_text('mod_version=1.0.1\n')
            self.assertNotEqual(first['content'], suite.source_identity(root)['content'])

    def test_ui_identifier_is_aop_ui_and_dependency_does_not_count_as_mod(self):
        path = Path('aopui-1.21.1-1.0.0.jar')
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="aop_ui"\nversion="1.0.0"\n')
        suite.validate_jar(path, '1.0.0', suite.MOD_IDS['ui'])
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="other"\nversion="1.0.0"\n[[dependencies.other]]\nmodId="aop_ui"\nversionRange="[1.0.0,)"\n')
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            suite.validate_jar(path, '1.0.0', suite.MOD_IDS['ui'])


if __name__ == '__main__':
    unittest.main()
