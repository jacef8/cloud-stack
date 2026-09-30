import json
from collections import defaultdict
S=json.load(open('seasons.json')); C=json.load(open('computed.json'))
ORDER=['2023','2024','2025','2026']
name={}
for s in ORDER:
    for uid,n in S[s]['users'].items(): name[uid]=n
def who(s,rid):
    uid=S[s]['r2u'].get(str(rid)) or S[s]['r2u'].get(rid); return name.get(uid,'?')
games,pairs,streaks=C['games'],C['pairs'],C['streaks']
def R(v,d=2): return round(v,d)

def rows(lst,n=8): return lst[:n]
S_ = lambda x:str(x)

stats=[]
g=[x for x in games if x['pts']>0]
stats.append(dict(id='hi',name='Highest single game',unit='points',dec=2,scale=240,
  rows=[[x['name'],R(x['pts']),f"Wk {x['w']}",int(x['s'])] for x in sorted(g,key=lambda x:-x['pts'])[:8]]))
stats.append(dict(id='blow',name='Biggest blowout',unit='margin',dec=2,scale=110,
  rows=[[p['win'],R(p['marg']),f"Wk {p['w']} over {p['lose']}",int(p['s'])] for p in sorted(pairs,key=lambda p:-p['marg'])[:8]]))
stats.append(dict(id='thin',name='Narrowest win',unit='margin',dec=2,scale=3,invert=True,asc=True,
  rows=[[p['win'],R(p['marg']),f"Wk {p['w']} over {p['lose']}",int(p['s'])] for p in sorted(pairs,key=lambda p:p['marg'])[:8]]))
stats.append(dict(id='loss',name='Most points in a loss',unit='points',dec=2,scale=200,
  rows=[[p['lose'],R(p['lp']),f"Wk {p['w']} lost to {p['win']}",int(p['s'])] for p in sorted(pairs,key=lambda p:-p['lp'])[:8]]))
stats.append(dict(id='streak',name='Longest win streak',unit='games',dec=0,scale=12,
  rows=[[x['name'],x['len'],f"Wk {x['from']}–{x['to']}",int(x['s'])] for x in sorted(streaks,key=lambda x:-x['len'])[:8]]))
tot=defaultdict(float)
for x in games:
    if x['reg']: tot[(x['s'],x['name'])]+=x['pts']
stats.append(dict(id='pf',name='Most points, regular season',unit='points',dec=2,scale=2200,
  rows=[[n,R(v),'14 games',int(s)] for (s,n),v in sorted(tot.items(),key=lambda x:-x[1])[:8]]))
stats.append(dict(id='bench',name='Most points left on the bench',unit='points',dec=2,scale=130,
  rows=[[x['name'],R(x['bench']),f"Wk {x['w']}, scored {R(x['pts'])}",int(x['s'])] for x in sorted(games,key=lambda x:-x['bench'])[:8]]))
wv=defaultdict(int); tr=defaultdict(int)
for s in ORDER:
    for t in S[s]['tx']:
        if t.get('status')!='complete': continue
        if t.get('type')=='waiver':
            for rid in (t.get('roster_ids') or []): wv[(s,who(s,rid))]+=1
        if t.get('type')=='trade':
            for rid in (t.get('roster_ids') or []): tr[(s,who(s,rid))]+=1
stats.append(dict(id='waiv',name='Most waiver claims, one season',unit='claims',dec=0,scale=40,off=True,
  rows=[[n,v,'won on waivers',int(s)] for (s,n),v in sorted(wv.items(),key=lambda x:-x[1])[:8]]))
stats.append(dict(id='trades',name='Most trades, one season',unit='trades',dec=0,scale=12,off=True,
  rows=[[n,v,'completed',int(s)] for (s,n),v in sorted(tr.items(),key=lambda x:-x[1])[:8]]))
AW={'hi':'Biggest game','blow':'Woodshed award','thin':'Heart attack award','loss':'Hard luck award',
    'streak':'Hot hand award','pf':'Scoring title','bench':'Wrong lineup award',
    'waiv':'Waiver wire award','trades':'Wheeler dealer'}
for s in stats: s['award']=AW[s['id']]

# managers + head to head (all games, all seasons)
w=defaultdict(int); l=defaultdict(int); pf=defaultdict(float); gp=defaultdict(int)
H=defaultdict(lambda: defaultdict(int)); MEET=defaultdict(lambda: defaultdict(int))
for p in pairs:
    w[p['win']]+=1; l[p['lose']]+=1
    H[p['win']][p['lose']]+=1
    MEET[p['win']][p['lose']]+=1; MEET[p['lose']][p['win']]+=1
for x in games: pf[x['name']]+=x['pts']; gp[x['name']]+=1
TITLES={}
for s in ORDER:
    fin=[m for m in S[s]['bracket'] if m.get('p')==1]
    if fin and fin[0].get('w'): TITLES[int(s)]=who(s,fin[0]['w'])
held=defaultdict(int)
for st in stats:
    if st['rows']: held[st['rows'][0][0]]+=1
seasons_of=defaultdict(set)
for x in games: seasons_of[x['name']].add(x['s'])
mgr=[]
for n in sorted(set(list(w)+list(l))):
    t=[y for y,c in TITLES.items() if c==n]
    mgr.append(dict(name=n,w=w[n],l=l[n],g=w[n]+l[n],ppg=R(pf[n]/max(1,gp[n]),1),
        titles=sorted(t),recs=held[n],seasons=sorted(int(z) for z in seasons_of[n])))
mgr.sort(key=lambda m:(-m['w'],-m['recs']))
out=dict(league='The 12 Tribes of Gridiron',prevName='May the Best Player Win',
    seasons=[int(s) for s in ORDER],current=2026,currentWeek=3,
    stats=stats,mgr=mgr,titles={str(k):v for k,v in TITLES.items()},
    h2h={a:{b:H[a][b] for b in MEET[a]} for a in MEET},
    meet={a:{b:MEET[a][b] for b in MEET[a]} for a in MEET},
    totalRecords=sum(len(s['rows']) for s in stats))
json.dump(out,open('data.json','w'))
print('managers:',len(mgr),'| stats:',len(stats),'| titles:',TITLES)
print('sanity — every pair series sums to its meetings:',
  all(H[a][b]+H[b][a]==MEET[a][b] for a in MEET for b in MEET[a]))
print('sanity — each manager W+L equals games:',all(m['w']+m['l']==m['g'] for m in mgr))
for m in mgr[:6]: print(f"  {m['name']:<16} {m['w']}-{m['l']}  titles {m['titles']}  records {m['recs']}")
