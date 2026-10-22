"""Build the four-mod suite in dependency order and reuse verified JARs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import tomllib
import zipfile

import release


STAGES = ('ui', 'core', 'tinkerer', 'compact')
ROOTS = {'ui': '.dependencies/aopui', 'core': '.',
         'tinkerer': '.dependencies/tinkerer', 'compact': '.dependencies/compact'}
DEPS = {'ui': (), 'core': ('ui',), 'tinkerer': ('ui', 'core'),
        'compact': ('ui', 'core', 'tinkerer')}
MOD_IDS = {'ui': 'aop_ui', 'core': 'thaumcraft', 'tinkerer': 'thaumictinkerer',
           'compact': 'thaumcraft_viewers'}
PATTERNS = {'ui': ('build/libs/aopui-*.jar',),
            'core': ('api/build/libs/thaumcraft-api-*.jar', 'mod/build/libs/thaumcraft-*.jar'),
            'tinkerer': ('build/libs/ThaumicTinkerer-*.jar',),
            'compact': ('build/libs/aoptc4-compact-*.jar',)}
CACHE = Path(os.environ.get('SUITE_CACHE_DIR', '.cache/mod-suite'))
FORMAT = 1
REPOSITORIES = {'ui': 'Salossov/aop-ui', 'core': 'Salossov/aop-thaumcraft-4',
                'tinkerer': 'Salossov/aoptc4-thaumic-tinkerer', 'compact': 'Salossov/aoptc4-compact'}
NAMES = {'ui': 'AOP: UI', 'core': 'AOP: Thaumcraft 4',
         'tinkerer': 'Thaumic Tinkerer', 'compact': 'AOP:TC4 Compact'}


def stages():
    target = os.environ.get('SUITE_TARGET')
    if target is None:
        return STAGES
    if target not in STAGES:
        raise ValueError('Invalid standalone target: ' + target)
    return (*DEPS[target], target)


def source_root(stage):
    target = os.environ.get('SUITE_TARGET')
    default = '.' if target == stage else ROOTS[stage]
    if target and stage == 'core' and target != 'core':
        default = '.dependencies/thaumcraft'
    return Path(os.environ.get('SUITE_' + stage.upper() + '_ROOT', default))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def identity(value):
    return digest(json.dumps(value, sort_keys=True, separators=(',', ':')).encode())


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def source_identity(root):
    # act uses explicit source inventories exported from the real checkouts.
    inventory = root / '.suite-source.json'
    if os.environ.get('ACT') == 'true' and inventory.is_file():
        data = read_json(inventory)
        revision, names = data['revision'], data['files']
    else:
        revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
        names = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z']).decode().split('\0')
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('Invalid source revision for ' + str(root))
    files = {}
    for name in sorted(filter(None, names)):
        path = root / name
        if Path(name).is_absolute() or '..' in Path(name).parts or path.is_symlink():
            raise ValueError('Unsafe source path: ' + name)
        files[name] = digest(path.read_bytes())
    if not files:
        raise ValueError('Empty source inventory: ' + str(root))
    props = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith(('#', '!')))
    return dict(revision=revision, content=identity(files), version=props['mod_version'].strip())


def prepare():
    sources = {stage: source_identity(source_root(stage)) for stage in stages()}
    for stage, source in sources.items():
        source['version'] = release.select_version('release', os.environ.get('SUITE_' + stage.upper() + '_VERSION', ''), source['version'], '')
    java = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True)
    runtime = dict(os=platform.system(), arch=platform.machine(),
                   java=java.stdout + java.stderr,
                   distro=platform.freedesktop_os_release() if platform.system() == 'Linux' else {})
    repositories = {stage: os.environ.get('SUITE_' + stage.upper() + '_REPOSITORY', default)
                    for stage, default in REPOSITORIES.items()}
    for repository in repositories.values():
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository name')
    lock = dict(format=FORMAT, sources=sources, runtime=runtime, repositories=repositories,
                mode='release' if os.environ.get('RELEASE_REQUESTED', '').lower() == 'true' else 'nightly',
                recipe=identity({name: digest(Path(__file__).with_name(name).read_bytes())
                                 for name in ('suite.py', 'release.py')}))
    lock['id'] = identity(lock)
    write_json(CACHE / 'lock.json', lock)
    print('Suite ' + lock['id'][:16])
    for stage, source in sources.items():
        print(stage + ': ' + source['version'] + ' @ ' + source['revision'])
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('id=' + lock['id'][:16] + '\n')


def plan(stage):
    lock = read_json(CACHE / 'lock.json')
    deps = {}
    for dependency in DEPS[stage]:
        manifest = verified(dependency)
        deps[dependency] = dict(key=manifest['key'], artifacts=manifest['artifacts'])
    outputs = {}
    for channel in ('release', 'nightly'):
        version = lock['sources'][stage]['version']
        if channel == 'nightly':
            version += '-nightly.' + lock['sources'][stage]['revision'][:12]
        value = dict(format=FORMAT, stage=stage, channel=channel, version=version,
                     source=lock['sources'][stage], runtime=lock['runtime'],
                     recipe=lock['recipe'], dependencies=deps)
        value['key'] = 'aop-suite-v1-' + stage + '-' + identity(value)
        write_json(CACHE / 'plans' / (stage + '-' + channel + '.json'), value)
        outputs[channel + '-key'] = value['key']
        print(stage + ' (' + channel + '): ' + value['key'])
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            for key, value in outputs.items():
                output.write(key + '=' + value + '\n')
    return outputs


def validate_jar(path, version, mod_id=None):
    if path.is_symlink() or not path.name.endswith(('-' + version + '.jar', '-' + version + '-sources.jar')):
        raise ValueError('Artifact filename/version mismatch: ' + str(path))
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError('Damaged JAR: ' + str(path))
        if any('/verification/' in name or '/productionVerification/' in name or
               name.startswith('aop/ui/verification/') for name in archive.namelist()):
            raise ValueError('Verification files leaked into ' + str(path))
        if mod_id is not None and not path.name.endswith('-sources.jar'):
            metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
            if not any(mod.get('modId') == mod_id and mod.get('version') == version
                       for mod in metadata.get('mods', [])):
                raise ValueError('Artifact metadata mismatch: ' + str(path))


def artifact_names(stage, version):
    prefix = {'ui': 'aopui-1.21.1-', 'core': 'thaumcraft-1.21.1-',
              'tinkerer': 'ThaumicTinkerer-1.21.1-', 'compact': 'aoptc4-compact-'}[stage]
    prefixes = [prefix] + (['thaumcraft-api-'] if stage == 'core' else [])
    return {prefix + version + suffix for prefix in prefixes for suffix in ('.jar', '-sources.jar')}


def verified_candidate(stage, channel, folder=None):
    expected = read_json(CACHE / 'plans' / (stage + '-' + channel + '.json'))
    folder = folder or CACHE / stage / channel
    manifest = read_json(folder / 'manifest.json')
    if manifest.get('plan') != expected or manifest.get('key') != expected['key']:
        raise ValueError('Cached source/dependency identity mismatch: ' + stage)
    artifacts = manifest.get('artifacts', {})
    if set(artifacts) != artifact_names(stage, expected['version']):
        raise ValueError('Incomplete artifact set: ' + stage)
    for name, checksum in artifacts.items():
        if Path(name).name != name:
            raise ValueError('Unsafe cached artifact path: ' + name)
        path = folder / name
        if digest(path.read_bytes()) != checksum:
            raise ValueError('Cached JAR checksum mismatch: ' + name)
        mod_id = None if name.startswith('thaumcraft-api-') else MOD_IDS[stage]
        validate_jar(path, expected['version'], mod_id)
    return manifest


def verified(stage):
    selected = read_json(CACHE / 'selected' / (stage + '.json'))
    lock = read_json(CACHE / 'lock.json')
    if lock['mode'] == 'release' and selected['channel'] != 'release':
        raise ValueError('An ordinary release cannot use nightly JARs: ' + stage)
    return verified_candidate(stage, selected['channel'])


def api(path, missing_ok=False):
    result = subprocess.run(['gh', 'api', path], capture_output=True, text=True)
    if result.returncode:
        if missing_ok and '(HTTP 404)' in result.stderr:
            return None
        raise RuntimeError(result.stderr or result.stdout)
    return json.loads(result.stdout)


def download_asset(repository, asset, destination):
    with destination.open('wb') as output:
        subprocess.run(['gh', 'api', 'repos/' + repository + '/releases/assets/' + str(asset['id']),
                        '-H', 'Accept: application/octet-stream'], stdout=output, check=True)


def matching_publication(repository, remote, manifest):
    assets = {asset['name']: asset for asset in remote['assets']}
    if 'aop-suite-manifest.json' not in assets or not set(manifest['artifacts']).issubset(assets):
        return False
    with tempfile.TemporaryDirectory(dir=CACHE) as directory:
        path = Path(directory) / 'manifest.json'
        download_asset(repository, assets['aop-suite-manifest.json'], path)
        if read_json(path) != manifest:
            return False
    return all(assets[name].get('digest') == 'sha256:' + checksum
               for name, checksum in manifest['artifacts'].items())


def fetch(stage):
    """Only import published artifacts whose complete provenance matches a plan."""
    if os.environ.get('ACT') == 'true' or os.environ.get('SUITE_FORCE_REBUILD') == 'true':
        return
    lock = read_json(CACHE / 'lock.json')
    repository = lock['repositories'][stage]
    channels = ('release',) if lock['mode'] == 'release' else ('release', 'nightly')
    tags = ['v' + lock['sources'][stage]['version']]
    if lock['mode'] == 'nightly':
        tags.append('nightly')
    for channel in channels:
        try:
            verified_candidate(stage, channel)
            return
        except (OSError, ValueError, KeyError, zipfile.BadZipFile):
            pass
    for tag in tags:
        remote = api('repos/' + repository + '/releases/tags/' + tag, missing_ok=True)
        if remote is None or remote.get('draft') or (lock['mode'] == 'release' and remote['prerelease']):
            continue
        assets = {asset['name']: asset for asset in remote['assets']}
        if 'aop-suite-manifest.json' not in assets:
            print(repository + '/' + tag + ': no verifiable suite provenance; building if needed.')
            continue
        with tempfile.TemporaryDirectory(dir=CACHE) as directory:
            folder = Path(directory)
            download_asset(repository, assets['aop-suite-manifest.json'], folder / 'manifest.json')
            manifest = read_json(folder / 'manifest.json')
            channel = manifest.get('plan', {}).get('channel')
            if channel not in channels:
                continue
            expected = read_json(CACHE / 'plans' / (stage + '-' + channel + '.json'))
            if manifest.get('plan') != expected or manifest.get('key') != expected['key']:
                print(repository + '/' + tag + ': source, toolchain or dependencies differ.')
                continue
            if not all(Path(name).name == name and name in assets for name in manifest['artifacts']):
                raise ValueError('Incomplete published suite assets: ' + repository)
            for name in manifest['artifacts']:
                download_asset(repository, assets[name], folder / name)
            try:
                verified_candidate(stage, channel, folder)
            except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
                print('Rejected published artifact ' + repository + '/' + tag + ': ' + str(error))
                continue
            destination = CACHE / stage / channel
            destination.mkdir(parents=True, exist_ok=True)
            for path in folder.iterdir():
                shutil.copyfile(path, destination / path.name)
            print('IMPORTED ' + stage + ' from ' + repository + '/' + tag, flush=True)
            return


def artifact(stage, prefix):
    manifest = verified(stage)
    matches = [name for name in manifest['artifacts']
               if name.startswith(prefix) and not name.endswith('-sources.jar')]
    if len(matches) != 1:
        raise ValueError('Ambiguous dependency artifact: ' + stage + '/' + prefix)
    return str((CACHE / stage / manifest['plan']['channel'] / matches[0]).resolve())


def build(stage, force=False):
    lock = read_json(CACHE / 'lock.json')
    channel = lock['mode']
    channels = ('release',) if channel == 'release' else ('release', 'nightly')
    reused = False
    if not force:
        for candidate in channels:
            try:
                verified_candidate(stage, candidate)
                channel, reused = candidate, True
                break
            except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
                print('Cache miss for ' + stage + '/' + candidate + ': ' + str(error), flush=True)
    expected = read_json(CACHE / 'plans' / (stage + '-' + channel + '.json'))
    if not reused:
        root = source_root(stage)
        args = ['sh', './gradlew', '--no-daemon', '--console=plain', '--no-problems-report', 'clean', 'build']
        args.append('-Pmod_version=' + expected['version'])
        if stage == 'core':
            args.append(':mod:compileProductionVerificationJava')
        if stage != 'ui':
            args.append('-PaopUiJar=' + artifact('ui', 'aopui-'))
        if stage in ('tinkerer', 'compact'):
            args.extend(['-PthaumcraftApiJar=' + artifact('core', 'thaumcraft-api-'),
                         '-PthaumcraftRuntimeJar=' + artifact('core', 'thaumcraft-1.21.1-')])
        if stage == 'compact':
            args.append('-PtinkererJar=' + artifact('tinkerer', 'ThaumicTinkerer-'))
        subprocess.run(args, cwd=root, check=True)
        paths = []
        for pattern in PATTERNS[stage]:
            matches = sorted(root.glob(pattern))
            if len(matches) != 2:
                raise ValueError('Expected gameplay/API and sources JARs for ' + pattern)
            paths.extend(matches)
        folder = CACHE / stage / channel
        folder.mkdir(parents=True, exist_ok=True)
        artifacts = {}
        for path in paths:
            validate_jar(path, expected['version'], None if path.name.startswith('thaumcraft-api-') else MOD_IDS[stage])
            target = folder / path.name
            shutil.copyfile(path, target)
            artifacts[path.name] = digest(target.read_bytes())
        write_json(folder / 'manifest.json', dict(key=expected['key'], plan=expected, artifacts=artifacts))
        verified_candidate(stage, channel)
    write_json(CACHE / 'selected' / (stage + '.json'), dict(channel=channel))
    status = 'REUSED' if reused else 'BUILT'
    print(status + ' ' + stage + ' (' + expected['version'] + ', ' + channel + ')', flush=True)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            store = os.environ.get('SUITE_' + channel.upper() + '_CACHE_HIT') != 'true'
            output.write('version=' + expected['version'] + '\nbuilt=' + str(not reused).lower() + '\nstore=' + str(store).lower()
                         + '\nchannel=' + channel + '\nkey=' + expected['key']
                         + '\npath=' + str(CACHE / stage / channel) + '\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('- **' + stage + '**: ' + status + ', `' + expected['version']
                          + '`, commit `' + expected['source']['revision'][:12] + '`\n')


def collect(stage=None):
    destination = Path('dist/suite')
    destination.mkdir(parents=True, exist_ok=True)
    manifests = {}
    sums = []
    for stage in ((stage,) if stage else stages()):
        manifest = verified(stage)
        manifests[stage] = manifest
        for name, checksum in manifest['artifacts'].items():
            shutil.copyfile(CACHE / stage / manifest['plan']['channel'] / name, destination / name)
            sums.append(checksum + '  ' + name)
    write_json(destination / 'manifest.json', dict(lock=read_json(CACHE / 'lock.json'), stages=manifests))
    (destination / 'SHA256SUMS.txt').write_text('\n'.join(sorted(sums)) + '\n', encoding='utf-8')
    print('Collected ' + str(len(sums)) + ' verified JARs with source and dependency provenance.')


def publish(dry_run=False, stage=None):
    lock = read_json(CACHE / 'lock.json')
    manifests = {item: verified(item) for item in ((stage,) if stage else stages())}
    if dry_run:
        print(json.dumps({stage: dict(repository=lock['repositories'][stage],
                                     version=manifest['plan']['version'],
                                     tag='nightly' if lock['mode'] == 'nightly' else 'v' + manifest['plan']['version'],
                                     prerelease=lock['mode'] == 'nightly')
                          for stage, manifest in manifests.items()}, indent=2))
        return
    existing = set()
    # Check all destinations before publishing any of them.
    for stage, manifest in manifests.items():
        repository = lock['repositories'][stage]
        tag = 'v' + manifest['plan']['version'] if lock['mode'] == 'release' else 'nightly'
        remote = api('repos/' + repository + '/releases/tags/' + tag, missing_ok=True)
        if remote is not None:
            same_channel = remote['prerelease'] == (lock['mode'] == 'nightly') and not remote['draft']
            if same_channel and matching_publication(repository, remote, manifest):
                existing.add(stage)
            elif lock['mode'] == 'release':
                raise ValueError('Existing release cannot be verified or overwritten: ' + repository + '/' + tag)
        elif lock['mode'] == 'release' and api('repos/' + repository + '/git/ref/tags/' + tag, missing_ok=True) is not None:
            raise ValueError('Existing tag cannot be overwritten: ' + repository + '/' + tag)
    previous = Path.cwd()
    previous_env = dict(os.environ)
    try:
        for stage, manifest in manifests.items():
            if stage in existing:
                print('ALREADY PUBLISHED ' + lock['repositories'][stage], flush=True)
                continue
            os.chdir(previous)
            source = (previous / source_root(stage)).resolve()
            package = (CACHE / 'publication' / stage).resolve()
            package.mkdir(parents=True, exist_ok=True)
            for name in manifest['artifacts']:
                shutil.copyfile(CACHE / stage / manifest['plan']['channel'] / name, package / name)
            write_json(package / 'aop-suite-manifest.json', manifest)
            (package / 'SHA256SUMS.txt').write_text('\n'.join(checksum + '  ' + name
                for name, checksum in sorted(manifest['artifacts'].items())) + '\n', encoding='utf-8')
            os.environ.update(RELEASE_MODE=lock['mode'], BUILD_VERSION=manifest['plan']['version'],
                              RELEASE_NAME=NAMES[stage], GH_REPO=lock['repositories'][stage],
                              GITHUB_SHA=manifest['plan']['source']['revision'],
                              RELEASE_REQUIREMENTS='Java 21 and NeoForge 21.1 are required. Build and dependency provenance is attached.')
            os.chdir(source)
            release.publish(assets_directory=package)
            print('PUBLISHED ' + lock['repositories'][stage], flush=True)
    finally:
        os.chdir(previous)
        os.environ.clear()
        os.environ.update(previous_env)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'plan', 'fetch', 'build', 'collect', 'publish'))
    parser.add_argument('stage', nargs='?', choices=STAGES)
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if args.command in ('plan', 'fetch', 'build') and args.stage is None:
        parser.error('stage is required')
    try:
        if args.command == 'build':
            build(args.stage, args.force or os.environ.get('SUITE_FORCE_REBUILD') == 'true')
        elif args.command == 'plan':
            plan(args.stage)
        elif args.command == 'fetch':
            fetch(args.stage)
        elif args.command == 'publish':
            publish(args.dry_run, args.stage)
        elif args.command == 'collect':
            collect(args.stage)
        else:
            globals()[args.command]()
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from error
