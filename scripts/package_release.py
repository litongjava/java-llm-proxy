"""Build Java kernel archives and portable desktop update manifests without publishing."""
import argparse
import hashlib
import io
import json
import re
import tarfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPOSITORY = 'https://github.com/litongjava/java-llm-proxy'
MIRROR = 'https://api.gitcode.com/api/v5/repos/ppnt/java-llm-proxy/releases'

def archive(path, files):
    if path.suffix == '.zip':
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as output:
            for name, content in files.items():
                output.writestr(name, content)
    else:
        with tarfile.open(path, 'w:gz') as output:
            for name, content in files.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                entry.mode = 0o755 if name.endswith('/LLMPoroxyUI') else 0o644
                output.addfile(entry, io.BytesIO(content))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_name(path.name + '.sha256').write_text(f'{digest}  {path.name}\n', encoding='utf-8')
    return digest

def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('target/release'))
    parser.add_argument('--desktop', action='append', default=[], help='windows-amd64=path/to/cpa-gui.exe (or linux-aarch64=path)')
    args = parser.parse_args()
    version = args.version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        parser.error('version must contain three numeric components')
    cargo = Path(__file__).resolve().parents[1] / 'frontend/src-tauri/Cargo.toml'
    desktop_version = re.search(r'^version\s*=\s*"([^"]+)"', cargo.read_text(encoding='utf-8'), re.M).group(1)
    if args.desktop and desktop_version != version:
        parser.error('A shared release requires matching Java, desktop and release versions; update Cargo.toml and rebuild the desktop first')
    jar = args.jar.read_bytes()
    with zipfile.ZipFile(io.BytesIO(jar)) as source:
        assert 'Main-Class:' in source.read('META-INF/MANIFEST.MF').decode()
        assert any(name in source.namelist() for name in ('nexus/io/llm/proxy/LLMProxyApp.class', 'BOOT-INF/classes/nexus/io/llm/proxy/LLMProxyApp.class'))
    args.output.mkdir(parents=True, exist_ok=True)
    kernels = {}
    for system in ('windows', 'linux', 'darwin'):
        for arch in ('amd64', 'aarch64'):
            suffix = 'zip' if system == 'windows' else 'tar.gz'
            path = args.output / f'java-llm-proxy_{version}_{system}_{arch}.{suffix}'
            archive(path, {'java-llm-proxy.jar': jar})
            kernels[f'{system}-{arch}'] = path
    catalogs = {}
    for specification in args.desktop:
        target, executable = specification.split('=', 1)
        system, arch = target.split('-')
        if system not in ('windows', 'linux') or target not in kernels:
            parser.error('portable desktop packaging supports windows/linux with amd64/aarch64; use Tauri DMG packaging for macOS')
        display = system.title()
        root = f'LLMPoroxyUI-v{desktop_version}-{display}-{arch}'
        binary = 'LLMPoroxyUI.exe' if system == 'windows' else 'LLMPoroxyUI'
        suffix = 'zip' if system == 'windows' else 'tar.gz'
        path = args.output / f'{root}.{suffix}'
        marker = dict(schemaVersion=1, application='LLMPoroxyUI', version=desktop_version, platform=system, arch=arch, autoUpdate=True)
        files = {f'{root}/{binary}': Path(executable).read_bytes(),
                 f'{root}/portable-app.json': json.dumps(marker).encode(),
                 f'{root}/core-version.txt': version.encode(),
                 f'{root}/cpa-core/{kernels[target].name}': kernels[target].read_bytes()}
        digest = archive(path, files)
        catalogs.setdefault(system, {})[target] = dict(url=f'{REPOSITORY}/releases/download/v{desktop_version}/{path.name}',
            fallbackUrls=[f'{MIRROR}/v{desktop_version}/attach_files/{path.name}/download'], sha256=digest, sizeBytes=path.stat().st_size)
    for system, assets in catalogs.items():
        manifest = dict(schemaVersion=1, version=desktop_version, publishedAt=datetime.now(timezone.utc).isoformat(),
            releaseUrl=f'{REPOSITORY}/releases/tag/v{desktop_version}', releaseNotes={}, assets=assets)
        (args.output / f'portable-update-{system}.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(f'Created release artifacts in {args.output.resolve()}; no files were uploaded.')

if __name__ == '__main__':
    run()
