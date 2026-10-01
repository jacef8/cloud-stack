import json
from collections import defaultdict
S=json.load(open('seasons.json')); C=json.load(open('computed.json'))
DONE=['2023','2024','2025']; CUR='2026'
name={}
for s in ['2023','2024','2025','2026']:
    for uid,n in S[s]['users'].items(): name[uid]=n
def who(s,rid):
    uid=S[s]['r2u'].get(str(rid)) or S[s]['r2u'].get(rid); return name.get(uid,'roster %s'%rid)
hist_games=[g for g in C['games'] if g['s'] in DONE]
hist_pairs=[p for p in C['pairs'] if p['s'] in DONE]
wv=defaultdict(int); tr=defaultdict(int)
for s in DONE:
    for t in S[s]['tx']:
        if t.get('status')!='complete': continue
        for rid in (t.get('roster_ids') or []):
            if t.get('type')=='waiver': wv[s+'|'+who(s,rid)]+=1
            elif t.get('type')=='trade': tr[s+'|'+who(s,rid)]+=1
titles={}
for s in DONE:
    fin=[m for m in S[s]['bracket'] if m.get('p')==1]
    if fin and fin[0].get('w'): titles[s]=who(s,fin[0]['w'])
hist=dict(
  done=[int(x) for x in DONE],
  games=[[g['s'],g['w'],g['name'],round(g['pts'],2),round(g['bench'],2),1 if g['reg'] else 0] for g in hist_games],
  pairs=[[p['s'],p['w'],p['win'],round(p['wp'],2),p['lose'],round(p['lp'],2),1 if p['reg'] else 0] for p in hist_pairs],
  waiv=dict(wv), trade=dict(tr), titles=titles,
  teams=json.load(open('teams.json')),
  current=dict(season=int(CUR),leagueId='1325921258503667712',pw=S[CUR]['pw']),
  league='The 12 Tribes of Gridiron')
json.dump(hist,open('history.json','w'),separators=(',',':'))
import os
print('history.json',round(os.path.getsize('history.json')/1024,1),'KB')
print('past games',len(hist['games']),'past pairs',len(hist['pairs']),'titles',titles)
