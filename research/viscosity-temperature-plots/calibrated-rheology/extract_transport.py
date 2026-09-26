"""Read producer cut transport data, retaining column positions and missing values."""
from pathlib import Path
import json,re,hashlib
import pdfplumber
ROOT=Path(__file__).resolve().parents[3]
OUT=Path(__file__).resolve().parent
def extract():
    records=[]
    for source in json.loads((ROOT/'research/crude-assays/source-data.json').read_text())['sources']:
        path=ROOT/'research/crude-assay-sources'/f"{source['id']}.pdf"
        assert hashlib.sha256(path.read_bytes()).hexdigest()==source['pdf_sha256']
        with pdfplumber.open(path) as pdf:
            words=pdf.pages[0].extract_words(x_tolerance=1)
            start=next(w for w in words if w['text']=='Start' and w['x0']<120)
            header=sorted([w for w in words if abs(w['top']-start['top'])<2 and w['x0']>140],key=lambda w:w['x0'])
            # Some layouts have a separate IBP-C5 light-end column after bulk IBP-FBP.
            c5=next(i for i,w in enumerate(header) if w['text']=='C5')
            header=[header[0]]+header[c5:]
            labels=[w['text'] for w in header];centers=[(w['x0']+w['x1'])/2 for w in header]
            assert labels==['IBP','C5','65','100','150','200','250','300','350','370','370','450','500','550'],labels
            columns=[{'start':s,'viscosity_points':[]} for s in labels]
            for word in words:
                if word['top']<=start['top'] or word['x0']>120:continue
                if word['text'] not in ['Viscosity','Pour','Cloud','Density','Total','Molecular','Volume','C7']:continue
                row=sorted([w for w in words if abs(w['top']-word['top'])<2],key=lambda w:w['x0'])
                txt=' '.join(w['text'] for w in row)
                match=re.search(r'Viscosity\s+@\s+(\d+)',txt)
                field=('pour_point_celsius' if txt.startswith('Pour Point') else
                       'cloud_point_celsius' if txt.startswith('Cloud Point') else
                       'density_15c_g_ml' if txt.startswith('Density @') else
                       'wax_mass_percent' if txt.startswith('Total Wax') else
                       'mw_g_mol' if txt.startswith('Molecular Weight') else
                       'vabp_celsius' if txt.startswith('Volume Average') else
                       'c7_asphaltenes_mass_percent' if txt.startswith('C7 Asphaltenes') else None)
                if not match and not field:continue
                for idx,center in enumerate(centers):
                    values=[float(w['text']) for w in row if re.fullmatch(r'-?\d+(?:\.\d+)?',w['text']) and abs((w['x0']+w['x1'])/2-center)<10]
                    assert len(values)<=1
                    if not values:continue
                    if match:columns[idx]['viscosity_points'].append([float(match[1]),values[0]])
                    else:columns[idx][field]=values[0]
            for c in columns:
                c['viscosity_points']=sorted(set(map(tuple,c['viscosity_points'])))
            # All important quality rows are rendered for visual column verification.
            pdf.pages[0].to_image(resolution=120).save(OUT/f"qa_{source['id']}.png")
        records.append({**{k:source[k] for k in ['id','name','reference','url','pdf_sha256']},
                        'whole':columns[0],'residue':columns[9],'cuts':columns[1:9]+columns[10:]})
    (OUT/'source_transport.json').write_text(json.dumps({'records':records},indent=2)+'\n')
    for r in records:
        print(r['id'],'wax whole/residue:',r['whole'].get('wax_mass_percent'),r['residue'].get('wax_mass_percent'),
              'cut viscosity points:',[len(c['viscosity_points']) for c in r['cuts']])
    return records
if __name__=='__main__':extract()
