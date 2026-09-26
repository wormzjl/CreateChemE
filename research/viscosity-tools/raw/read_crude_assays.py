from pathlib import Path
import urllib.request
import hashlib
import pdfplumber

folder=Path('research/crude-assay-sources');folder.mkdir(parents=True,exist_ok=True)
urls={
 'wti_light':'https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/wti_light.pdf',
 'upper_zakum':'https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/upper_zakum.pdf',
 'bonga':'https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2025/bonga.pdf',
 'dalia':'https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/dalia.pdf',
 'cold_lake_blend':'https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2024/cold_lake_blend.pdf'}
for name,url in urls.items():
 path=folder/(name+'.pdf')
 if not path.exists():
  request=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
  path.write_bytes(urllib.request.urlopen(request,timeout=30).read())
 with pdfplumber.open(path) as pdf:
  text='\n\n'.join(p.extract_text(layout=True) for p in pdf.pages)
  (folder/(name+'.txt')).write_text(text,encoding='utf-8')
 print(name,hashlib.sha256(path.read_bytes()).hexdigest())
