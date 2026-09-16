"""Deterministic prototype models using Minecraft's own textures; no external image tooling."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = ROOT / 'assets/createcheme'

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

def box(start, end, texture='body', faces=None):
    return {'from': start, 'to': end, 'faces': {face: {'texture': '#' + (faces or {}).get(face, texture)} for face in ['down','up','north','south','west','east']}}

def model(name, elements, textures):
    write(ASSETS / f'models/block/{name}.json', {'parent': 'minecraft:block/block', 'textures': {'particle': textures['body'], **textures}, 'elements': elements})

IRON = 'minecraft:block/iron_block'
model('fluid_reservoir', [box([1,0,1],[15,2,15]), box([1,14,1],[15,16,15]), box([2,2,2],[14,14,14],'glass')] + [box([x,2,z],[x+2,14,z+2]) for x in [1,13] for z in [1,13]], {'body':IRON, 'glass':'minecraft:block/light_blue_stained_glass'})
tank_path=ASSETS/'models/block/fluid_reservoir.json'
tank_model=json.loads(tank_path.read_text(encoding='utf-8'))
tank_model['render_type']='minecraft:translucent'
write(tank_path,tank_model)
model('fluid_pipe_core', [box([5,5,5],[11,11,11])], {'body':IRON})
model('fluid_pipe_arm', [box([6,6,0],[10,10,6])], {'body':IRON})
model('fluid_pipe', [box([5,5,5],[11,11,11]),box([6,6,0],[10,10,16])], {'body':IRON})
model('fluid_pump', [box([2,2,3],[14,13,13]),box([5,5,0],[11,11,16],faces={'north':'out','south':'in'}),box([4,13,5],[12,16,11],'motor')], {'body':IRON,'in':'minecraft:block/red_concrete','out':'minecraft:block/lime_concrete','motor':'minecraft:block/copper_block'})
model('pressure_control_valve', [box([4,4,3],[12,12,13]),box([6,6,0],[10,10,16],faces={'north':'out','south':'in'}),box([7,11,7],[9,14,9]),box([2,13,2],[14,15,14],'wheel')], {'body':IRON,'in':'minecraft:block/red_concrete','out':'minecraft:block/lime_concrete','wheel':'minecraft:block/gold_block'})
model('fluid_generator',[box([1,0,1],[15,2,15]),box([2,2,2],[14,14,14],'source'),box([1,14,1],[15,16,15])],{'body':IRON,'source':'minecraft:block/emerald_block'})
model('fluid_void',[box([1,0,1],[15,2,15]),box([2,2,2],[14,14,14],'sink'),box([1,14,1],[15,16,15])],{'body':IRON,'sink':'minecraft:block/crying_obsidian'})

rotation={'north':{},'east':{'y':90},'south':{'y':180},'west':{'y':270},'up':{'x':270},'down':{'x':90}}
names={'fluid_reservoir':'Fluid Reservoir','fluid_pipe':'Process Fluid Pipe','fluid_pump':'Process Fluid Pump','pressure_control_valve':'Pressure Control Valve','fluid_generator':'Fluid Generator','fluid_void':'Fluid Void'}
for name, title in names.items():
    if name == 'fluid_pipe':
        state={'multipart':[{'apply':{'model':'createcheme:block/fluid_pipe_core'}}] + [{'when':{d:'true'},'apply':{'model':'createcheme:block/fluid_pipe_arm',**r}} for d,r in rotation.items()]}
    else:
        state={'variants':{f'facing={d}':{'model':f'createcheme:block/{name}',**r} for d,r in rotation.items()}}
    write(ASSETS/f'blockstates/{name}.json',state)
    write(ASSETS/f'models/item/{name}.json',{'parent':f'createcheme:block/{name}'})
    write(ROOT/f'data/createcheme/loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'createcheme:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
write(ASSETS/'models/item/fluid_debugger.json',{'parent':'minecraft:item/handheld','textures':{'layer0':'minecraft:item/clock_00'}})
lang_path=ASSETS/'lang/en_us.json'
lang=json.loads(lang_path.read_text(encoding='utf-8')) if lang_path.exists() else {}
lang.update({f'block.createcheme.{name}':title for name,title in names.items()})
lang['item.createcheme.fluid_debugger']='Fluid Network Probe'
write(lang_path,lang)
write(ROOT/'data/create/tags/block/non_movable.json',{'replace':False,'values':[f'createcheme:{name}' for name in names]})
write(ROOT/'data/minecraft/tags/block/mineable/pickaxe.json',{'replace':False,'values':[f'createcheme:{name}' for name in names]})
