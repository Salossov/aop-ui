"""Version selection, artifact validation and GitHub release publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import quote
import zipfile


STABLE = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
VERSION = STABLE + r"(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"


def properties():
    return dict((key.strip(), value.strip()) for line in Path('gradle.properties').read_text().splitlines()
                if '=' in line and not line.lstrip().startswith(('#', '!'))
                for key, value in [line.split('=', 1)])


def select_version(mode, override, configured, run_number):
    version = override.strip() or configured
    if not re.fullmatch(VERSION, version):
        raise ValueError('Version must use major.minor.patch, for example 1.0.0.')
    if mode == 'release' and not re.fullmatch(STABLE, version):
        raise ValueError('An ordinary release requires major.minor.patch without a prerelease or build suffix.')
    if mode == 'nightly':
        if not run_number.isdigit():
            raise ValueError('A nightly build requires a numeric GITHUB_RUN_NUMBER.')
        version = re.match(STABLE, version).group() + '-nightly.' + run_number
    return version


def select_mode(event, requested_release):
    if event == 'pull_request':
        return 'build'
    if event == 'workflow_dispatch':
        return 'release' if requested_release else 'nightly'
    if event == 'push':
        return 'nightly'
    raise ValueError('Unsupported release event: ' + event)


def prepare():
    mode = select_mode(os.environ['GITHUB_EVENT_NAME'], os.environ.get('RELEASE_REQUESTED', 'false').lower() == 'true')
    version = select_version(mode, os.environ.get('RELEASE_VERSION', ''),
                             properties()['mod_version'], os.environ.get('GITHUB_RUN_NUMBER', ''))
    for file, entries in [('GITHUB_ENV', [('RELEASE_MODE', mode), ('BUILD_VERSION', version)]),
                          ('GITHUB_OUTPUT', [('mode', mode), ('version', version)])]:
        with open(os.environ[file], 'a', encoding='utf-8') as output:
            for key, value in entries:
                output.write(key + '=' + value + '\n')
    print('Publication mode: ' + mode + '; build version: ' + version)


def collect():
    version = os.environ['BUILD_VERSION']
    paths = set()
    for pattern in filter(None, map(str.strip, os.environ['RELEASE_ARTIFACTS'].splitlines())):
        matches = list(Path('.').glob(pattern))
        if not matches:
            raise ValueError('Missing release artifact: ' + pattern)
        paths.update(matches)
    if not paths:
        raise ValueError('No release artifacts were produced.')
    destination = Path('dist')
    destination.mkdir(exist_ok=False)
    checksums = []
    for source in sorted(paths):
        if not source.name.endswith(('-' + version + '.jar', '-' + version + '-sources.jar')):
            raise ValueError('Artifact version differs from selected version: ' + str(source))
        with zipfile.ZipFile(source) as archive:
            if archive.testzip() is not None:
                raise ValueError('Damaged JAR: ' + str(source))
            if any('/verification/' in name or name.startswith('aop/ui/verification/')
                   for name in archive.namelist()):
                raise ValueError('Verification sources or classes leaked into ' + str(source))
            if not source.name.endswith('-sources.jar') and not source.name.startswith('thaumcraft-api-'):
                metadata = archive.read('META-INF/neoforge.mods.toml').decode('utf-8')
                if not re.search(r'^version\s*=\s*[\"\']' + re.escape(version) + r'[\"\']\s*$', metadata, re.M):
                    raise ValueError('Mod metadata differs from selected version: ' + str(source))
        target = destination / source.name
        shutil.copyfile(source, target)
        checksums.append(hashlib.sha256(target.read_bytes()).hexdigest() + '  ' + target.name)
    (destination / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
    print('Collected ' + str(len(paths)) + ' validated artifacts.')


def release_plan():
    mode = os.environ['RELEASE_MODE']
    if mode not in ('release', 'nightly'):
        raise ValueError('Publication requires release or nightly mode.')
    version = os.environ['BUILD_VERSION']
    if mode == 'release' and not re.fullmatch(STABLE, version):
        raise ValueError('Refusing to publish a non-stable version as an ordinary release.')
    nightly = mode == 'nightly'
    return dict(tag='nightly' if nightly else 'v' + version, prerelease=nightly, draft=False,
                title='Nightly' if nightly else os.environ['RELEASE_NAME'] + ' ' + version,
                latest=not nightly, target=os.environ['GITHUB_SHA'])


def commit_notes(repository, target):
    """Include every commit since the nearest published stable ancestor release."""
    result = subprocess.run(['gh', 'api', '--paginate', 'repos/' + repository + '/releases',
                             '--jq', '.[] | select(.draft == false and .prerelease == false) | .tag_name'],
                            capture_output=True, text=True, check=True)
    ancestors = []
    for tag in result.stdout.splitlines():
        resolved = subprocess.run(['git', 'rev-parse', '--verify', 'refs/tags/' + tag + '^{commit}'],
                                  capture_output=True, text=True, check=True).stdout.strip()
        relation = subprocess.run(['git', 'merge-base', '--is-ancestor', resolved, target], capture_output=True)
        if relation.returncode == 1:
            continue
        if relation.returncode:
            raise RuntimeError('Could not determine the previous release ancestry.')
        count = int(subprocess.run(['git', 'rev-list', '--count', resolved + '..' + target],
                                   capture_output=True, text=True, check=True).stdout.strip())
        ancestors.append((count, tag, resolved))
    previous = min(ancestors) if ancestors else None
    revision = previous[2] + '..' + target if previous else target
    log = subprocess.run(['git', 'log', '--reverse', '--format=%H%x09%s', revision],
                         capture_output=True, text=True, check=True).stdout
    lines = ['## Commits', '']
    for entry in log.splitlines():
        sha, subject = entry.split('\t', 1)
        subject = re.sub(r'([\\`*_[\]<>])', r'\\\1', subject)
        lines.append('- [`' + sha[:7] + '`](https://github.com/' + repository + '/commit/' + sha + ') ' + subject)
    if not log.strip():
        lines.append('No new commits since the previous ordinary release.')
    if previous:
        lines.extend(['', '[Full comparison](https://github.com/' + repository + '/compare/'
                      + quote(previous[1], safe='') + '...' + target + ')'])
    return '\n'.join(lines) + '\n'


def publish(dry_run=False, assets_directory='dist'):
    plan = release_plan()
    if dry_run:
        print(json.dumps(plan, indent=2))
        return
    repository = os.environ['GH_REPO']
    assets = sorted(Path(assets_directory).iterdir())
    if not assets or not any(path.suffix == '.jar' for path in assets):
        raise ValueError('No packaged JARs to publish.')

    def api(path, method='GET', missing_ok=False):
        result = subprocess.run(['gh', 'api', '--method', method, 'repos/' + repository + '/' + path],
                                capture_output=True, text=True)
        if result.returncode:
            if missing_ok and '(HTTP 404)' in result.stderr:
                return None
            raise RuntimeError(result.stderr or result.stdout)
        return json.loads(result.stdout) if result.stdout.strip() else None

    old = api('releases/tags/' + plan['tag'], missing_ok=True)
    tag = api('git/ref/tags/' + plan['tag'], missing_ok=True)
    if not plan['prerelease'] and (old is not None or tag is not None):
        raise ValueError('This release or tag already exists. Select a new version; nothing was overwritten.')
    if plan['prerelease']:
        if old is not None:
            api('releases/' + str(old['id']), method='DELETE')
        if tag is not None:
            api('git/refs/tags/' + plan['tag'], method='DELETE')
    notes = Path(os.environ['RUNNER_TEMP']) / 'aop-release-notes.md'
    changes = '' if plan['prerelease'] else '\n' + commit_notes(repository, plan['target'])
    notes.write_text(os.environ['RELEASE_NAME'] + ' for Minecraft ' + properties()['minecraft_version']
                     + ' / NeoForge.\n\nVersion: `' + os.environ['BUILD_VERSION'] + '`\n\nCommit: `'
                     + plan['target'] + '`\n\n' + os.environ['RELEASE_REQUIREMENTS'] + '\n'
                     + ('\nDevelopment snapshot; replaces the previous nightly.\n' if plan['prerelease'] else changes),
                     encoding='utf-8')
    subprocess.run(['gh', 'release', 'create', plan['tag'], '--repo', repository, '--target', plan['target'],
                    '--title', plan['title'], '--notes-file', str(notes), '--draft=false',
                    '--prerelease=' + str(plan['prerelease']).lower(), '--latest=' + str(plan['latest']).lower(),
                    *map(str, assets)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'collect', 'publish'])
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'publish':
            publish(args.dry_run)
        else:
            globals()[args.command]()
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from error
