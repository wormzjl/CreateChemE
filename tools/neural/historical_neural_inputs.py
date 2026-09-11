"""Account for historical input populations from verified retained manifests."""
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import digest

MANIFESTS = ('gen2-cache-manifest.json','gen3-cache-manifest.json','transformer-cache-manifest.json',
             'unified-evaluation-cache-manifest.json','transformer-accuracy-cache-manifest.json')


def canonical_digest(hashes):
    return hashlib.sha256(('\n'.join(sorted(hashes))+'\n').encode('utf-8')).hexdigest()


def _inputs(value):
    if isinstance(value, dict):
        if isinstance(value.get('input'),dict) and 'specifications' in value['input']:
            yield value['input']
        for key, nested in value.items():
            if key != 'input': yield from _inputs(nested)
    elif isinstance(value,list):
        for nested in value: yield from _inputs(nested)


def population(data, name):
    if name.endswith('.gz'): data = gzip.decompress(data); name = name[:-3]
    text = data.decode('utf-8-sig')
    values = [json.loads(line) for line in text.splitlines() if line.strip()] if name.endswith('.jsonl') else [json.loads(text)]
    hashes, count = set(), 0
    for value in values:
        for inp in _inputs(value):
            hashes.add(canonical_input_hash(inp)); count += 1
    return hashes, {'records':len(values),'inputRecords':count,'uniqueInputs':len(hashes),
                    'canonicalInputHashesSha256':canonical_digest(hashes)}


def verified_bytes(data, checksum, size=None):
    if hashlib.sha256(data).hexdigest() != checksum or (size is not None and len(data) != size):
        raise ValueError('Historical archive entry does not match its manifest')
    return data


def read_required_population(path, checksum, size=None):
    # Missing files are fatal even if a partial build tree happens to exist.
    value = verified_bytes(Path(path).read_bytes(), checksum, size)
    return population(value, str(path))


def archived_entry(root, manifest_name, entry_name):
    root = Path(root)
    manifest = json.loads((root/'tools/neural'/manifest_name).read_text(encoding='utf-8-sig'))
    archive = root/manifest['archivePath']
    if digest(archive) != manifest['archiveSha256']: raise ValueError('Required historical archive changed')
    entry = next(r for r in manifest['entries'] if r['entry'] == entry_name)
    with zipfile.ZipFile(archive) as source:
        return verified_bytes(source.read(entry_name),entry['sha256'],entry.get('bytes'))


def collect(root):
    root = Path(root)
    hashes, populations, manifests, parsed = set(), [], [], {}

    def add(data, name, checksum, identity, provenance):
        if not name.endswith(('.json','.jsonl','.jsonl.gz')): return
        key = (checksum, '.jsonl.gz' if name.endswith('.jsonl.gz') else Path(name).suffix)
        if key not in parsed: parsed[key] = population(data,name)
        values, counts = parsed[key]
        if values:
            hashes.update(values)
            populations.append({'identity':identity,'contentSha256':checksum,**counts,'provenance':provenance})

    for name in MANIFESTS:
        path = root/'tools/neural'/name
        manifest = json.loads(path.read_text(encoding='utf-8-sig'))
        manifests.append({'path':path.relative_to(root).as_posix(),'sha256':digest(path)})
        if name == 'gen2-cache-manifest.json':
            if not any(r['cachedPath']=='study/design/candidate-matrix.jsonl' for r in manifest['files']):
                raise ValueError('Gen2 manifest omits its complete candidate population')
            for entry in manifest['files']:
                filename = entry['cachedPath']
                if not filename.endswith(('.json','.jsonl','.jsonl.gz')): continue
                cached = root/manifest['cacheRoot']/filename
                value = verified_bytes(cached.read_bytes(),entry['sha256'],entry.get('bytes'))
                add(value,filename,entry['sha256'],name+'::'+filename,
                    {'manifest':name,'cachedPath':cached.relative_to(root).as_posix(),'sourcePath':entry['sourcePath']})
            continue
        if name == 'unified-evaluation-cache-manifest.json':
            archives = [manifest['studyArchive'],manifest['dependencyArchive']]
        elif name == 'gen3-cache-manifest.json':
            archives = [{'path':manifest['archivePath'],'sha256':manifest['archiveSha256'],
                         'entries':[{'entry':r['archiveEntry'],**{k:v for k,v in r.items() if k!='archiveEntry'}} for r in manifest['files']]}]
        else:
            archives = [{'path':manifest['archivePath'],'sha256':manifest['archiveSha256'],'entries':manifest['entries']}]
        for record in archives:
            archive = root/record['path']
            if digest(archive) != record['sha256']: raise ValueError('Required historical archive changed or is unavailable')
            with zipfile.ZipFile(archive) as source:
                for entry in record['entries']:
                    filename = entry['entry']
                    if not filename.endswith(('.json','.jsonl','.jsonl.gz')): continue
                    value = verified_bytes(source.read(filename),entry['sha256'],entry.get('bytes'))
                    add(value,filename,entry['sha256'],name+'::'+filename,
                        {'manifest':name,'archivePath':record['path'],'archiveSha256':record['sha256'],'entry':filename})
    if not hashes: raise ValueError('No verified historical inputs were accounted for')
    return hashes, populations, manifests


def new_pool(candidates, historical):
    hashes = [canonical_input_hash(row['input']) for row in candidates]
    if len(hashes) != len(set(hashes)) or set(hashes) & set(historical):
        raise ValueError('New pool contains a historical or duplicate input')
    return hashes


def fresh_test(rows, historical):
    if len(rows) != 252 or len({row['id'] for row in rows}) != 252:
        raise ValueError('Fresh test requires 252 unique case IDs')
    if any(set(row) != {'id','input','design','split'} or row['split'] != 'test' for row in rows):
        raise ValueError('Fresh test must contain only input/design metadata, never teacher outcomes')
    new_pool(rows,historical)
    strata = Counter((row['input']['stageCount'],bool(row['input'].get('steamFeeds'))) for row in rows)
    expected = {(stage,steam):2 for stage in range(2,65) for steam in (False,True)}
    if dict(strata) != expected: raise ValueError('Fresh test stage/steam strata are not the registered 4-per-stage design')
