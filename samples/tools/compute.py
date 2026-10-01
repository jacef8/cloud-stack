import json
from collections import defaultdict
S=json.load(open('seasons.json'))
ORDER=['2023','2024','2025','2026']
name={}
for s in ORDER:
    for uid,n in S[s]['users'].items(): name[uid]=n   # latest display name wins
def who(s,rid):
    uid=S[s]['r2u'].get(str(rid)) or S[s]['r2u'].get(rid)
    return name.get(uid,'roster %s'%rid), uid

games=[]          # every team-week
pairs=[]          # head-to-head results
for s in ORDER:
    pw=S[s]['pw']
    for w,ms in sorted(S[s]['weeks'].items(),key=lambda x:int(x[0])):
        w=int(w)
        by=defaultdict(list)
        for m in ms: by[m.get('matchup_id')].append(m)
        for mid,side in by.items():
            for m in side:
                nm,uid=who(s,m['roster_id'])
                pts=m.get('points') or 0
                st=set(m.get('starters') or [])
                pp=m.get('players_points') or {}
                bench=round(sum(v for k,v in pp.items() if k not in st),2)
                games.append({'s':s,'w':w,'name':nm,'uid':uid,'pts':round(pts,2),
                              'bench':bench,'reg':w<pw})
            if mid is not None and len(side)==2:
                a,b=side
                an,_=who(s,a['roster_id']); bn,_=who(s,b['roster_id'])
                ap=round(a.get('points') or 0,2); bp=round(b.get('points') or 0,2)
                if ap==bp: continue
                win,lose=((an,ap),(bn,bp)) if ap>bp else ((bn,bp),(an,ap))
                pairs.append({'s':s,'w':w,'win':win[0],'wp':win[1],'lose':lose[0],
                              'lp':lose[1],'marg':round(abs(ap-bp),2),'reg':w<pw})

def top(rows,key,n=8,rev=True):
    return sorted(rows,key=lambda r:r[key],reverse=rev)[:n]

print('=== HIGHEST SINGLE GAME ===')
for g in top([g for g in games if g['pts']>0],'pts'):
    print(f"  {g['pts']:>7.2f}  {g['name']:<16} {g['s']} wk{g['w']}")
print('=== BIGGEST BLOWOUT ===')
for p in top(pairs,'marg'):
    print(f"  {p['marg']:>7.2f}  {p['win']:<16} over {p['lose']:<16} {p['s']} wk{p['w']}")
print('=== NARROWEST WIN ===')
for p in top(pairs,'marg',8,False):
    print(f"  {p['marg']:>7.2f}  {p['win']:<16} over {p['lose']:<16} {p['s']} wk{p['w']}")
print('=== MOST POINTS IN A LOSS ===')
for p in top(pairs,'lp'):
    print(f"  {p['lp']:>7.2f}  {p['lose']:<16} lost to {p['win']:<16} {p['s']} wk{p['w']}")
print('=== MOST POINTS ON THE BENCH (one week) ===')
for g in top(games,'bench'):
    print(f"  {g['bench']:>7.2f}  {g['name']:<16} {g['s']} wk{g['w']}  (scored {g['pts']})")
print('=== MOST POINTS, REGULAR SEASON ===')
tot=defaultdict(float);cnt=defaultdict(int)
for g in games:
    if g['reg']: tot[(g['s'],g['name'])]+=g['pts'];cnt[(g['s'],g['name'])]+=1
for (s,n),v in sorted(tot.items(),key=lambda x:-x[1])[:8]:
    print(f"  {v:>8.2f}  {n:<16} {s}  ({cnt[(s,n)]} games)")
print('=== LONGEST WIN STREAK (regular season, within a year) ===')
res=defaultdict(list)
for p in sorted(pairs,key=lambda p:(p['s'],p['w'])):
    if not p['reg']: continue
    res[(p['s'],p['win'])].append((p['w'],1)); res[(p['s'],p['lose'])].append((p['w'],0))
streaks=[]
for (s,n),ws in res.items():
    ws.sort(); cur=0;best=0;st=0;bs=be=0
    for w,r in ws:
        if r: 
            cur+=1
            if cur==1: st=w
            if cur>best: best,bs,be=cur,st,w
        else: cur=0
    streaks.append({'s':s,'name':n,'len':best,'from':bs,'to':be})
for x in top(streaks,'len'):
    print(f"  {x['len']:>3}  {x['name']:<16} {x['s']} wk{x['from']}–{x['to']}")
print('=== BIGGEST FAAB BID ===')
bids=[]
for s in ORDER:
    for t in S[s]['tx']:
        if t.get('type')!='waiver': continue
        bid=(t.get('settings') or {}).get('waiver_bid')
        if not bid: continue
        rid=(t.get('roster_ids') or [None])[0]
        nm,_=who(s,rid)
        bids.append({'s':s,'name':nm,'bid':bid,'w':t.get('leg'),'adds':list((t.get('adds') or {}).keys())})
for b in top(bids,'bid'):
    print(f"  ${b['bid']:>4}  {b['name']:<16} {b['s']} wk{b['w']}")
print('=== TRADES IN A SEASON ===')
tr=defaultdict(int)
for s in ORDER:
    for t in S[s]['tx']:
        if t.get('type')=='trade' and t.get('status')=='complete':
            for rid in (t.get('roster_ids') or []):
                nm,_=who(s,rid); tr[(s,nm)]+=1
for (s,n),v in sorted(tr.items(),key=lambda x:-x[1])[:8]:
    print(f"  {v:>3}  {n:<16} {s}")
print('=== CHAMPIONS ===')
for s in ORDER:
    br=S[s]['bracket']
    fin=[m for m in br if m.get('p')==1]
    if fin and fin[0].get('w'):
        nm,_=who(s,fin[0]['w']); rn,_=who(s,fin[0].get('l'))
        print(f"  {s}: {nm}  (beat {rn})")
    else: print(f"  {s}: not decided yet")
json.dump({'games':games,'pairs':pairs,'streaks':streaks,'bids':bids},open('computed.json','w'))
