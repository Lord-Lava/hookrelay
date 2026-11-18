#!/usr/bin/env python3
"""Review Gradle SHA-256 entries against official repository checksums/downloads.

This does not authenticate publisher signing keys or replace advisory review. It never
modifies verification metadata. Run explicitly when reviewing dependency changes.
"""
import argparse
import concurrent.futures
import hashlib
import json
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

REPOSITORIES = ('https://repo.maven.apache.org/maven2/', 'https://plugins.gradle.org/m2/')


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def verify(item):
    group, name, version, artifact, expected = item
    path = f"{group.replace('.', '/')}/{name}/{version}/{artifact}"
    # Plugin marker POMs can legitimately differ between the Portal and Central.
    repositories = REPOSITORIES[::-1] if name.endswith('.gradle.plugin') else REPOSITORIES
    urls = [repository + path for repository in repositories]
    # Gradle distributions are not Maven artifacts; verify their published checksums
    # directly without treating arbitrary coordinates as distribution downloads.
    if (group, name) == ('gradle', 'gradle') and artifact in (
            f'gradle-{version}-src.zip', f'gradle-{version}-bin.zip',
            f'gradle-{version}-all.zip'):
        urls = [f'https://downloads.gradle.org/distributions/{artifact}']
    for url in urls:
        try:
            try:
                if name.endswith('.gradle.plugin'):
                    # The Portal's checksum URL may redirect to Central's different POM.
                    digest = hashlib.sha256(fetch(url)).hexdigest()
                    method = 'official-marker-download'
                else:
                    digest = fetch(url + '.sha256').decode().strip().split()[0].lower()
                    method = 'published-sha256'
                if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                    raise ValueError('Invalid published SHA-256')
            except urllib.error.HTTPError as error:
                if error.code not in (403, 404):
                    raise
                digest = hashlib.sha256(fetch(url)).hexdigest()
                method = 'official-artifact-download'
            if digest not in expected:
                return {'artifact': path, 'status': 'MISMATCH', 'source': url}
            return {'artifact': path, 'status': 'verified', 'method': method, 'source': url,
                    'sha256': digest}
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            last_error = type(error).__name__
    return {'artifact': path, 'status': 'unverified', 'error': last_error}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', default='backend/gradle/verification-metadata.xml')
    parser.add_argument('--report', default='backend/build/reports/dependency-provenance.json')
    args = parser.parse_args()
    root = ET.parse(args.metadata).getroot()
    ns = {'g': root.tag.split('}')[0].lstrip('{')}
    items = []
    for component in root.findall('g:components/g:component', ns):
        for artifact in component.findall('g:artifact', ns):
            hashes = [h.attrib['value'] for h in artifact.findall('g:sha256', ns)]
            if not hashes:
                raise SystemExit(f"Missing SHA-256: {artifact.attrib['name']}")
            items.append((component.attrib['group'], component.attrib['name'],
                          component.attrib['version'], artifact.attrib['name'], hashes))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(verify, items))
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(results, indent=2) + '\n')
    failures = [r for r in results if r['status'] != 'verified']
    print(f"Official repository verification: {len(results) - len(failures)}/{len(results)} artifacts")
    for failure in failures:
        print(failure)
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
