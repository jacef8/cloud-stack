import json,urllib.request,ssl
ctx=ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
def get(u):
    try:
        r=urllib.request.Request(u,headers={'User-Agent':'record-book/1.0'})
        with urllib.request.urlopen(r,timeout=30,context=ctx) as f: return json.load(f)
    except Exception: return None

LEAGUES=[('2023','1001022922321977344'),('2024','1124845472708591616'),
         ('2025','1219702278403932160'),('2026','1325921258503667712')]
seasons={}
for season,lid in LEAGUES:
    L=get(f'https://api.sleeper.app/v1/league/{lid}')
    users={u['user_id']:(u.get('display_name') or u['user_id']) for u in (get(f'https://api.sleeper.app/v1/league/{lid}/users') or [])}
    teamname={u['user_id']:((u.get('metadata') or {}).get('team_name') or '') for u in (get(f'https://api.sleeper.app/v1/league/{lid}/users') or [])}
    rosters=get(f'https://api.sleeper.app/v1/league/{lid}/rosters') or []
    r2u={r['roster_id']:r.get('owner_id') for r in rosters}
    pw=L['settings'].get('playoff_week_start',15)
    weeks={}
    for w in range(1,pw+4):
        m=get(f'https://api.sleeper.app/v1/league/{lid}/matchups/{w}')
        if not m: break
        if not any((x.get('points') or 0)>0 for x in m):
            if w<=3: continue
            break
        weeks[w]=m
    tx=[]
    for w in range(1,pw+4):
        t=get(f'https://api.sleeper.app/v1/league/{lid}/transactions/{w}')
        if t: tx+=t
    seasons[season]={'lid':lid,'users':users,'teamname':teamname,'r2u':r2u,'pw':pw,
        'weeks':weeks,'tx':tx,'bracket':get(f'https://api.sleeper.app/v1/league/{lid}/winners_bracket') or [],
        'status':L['status'],'teams':L['settings']['num_teams']}
    print(season,'teams',L['settings']['num_teams'],'weeks',sorted(weeks),'tx',len(tx))
json.dump(seasons,open('seasons.json','w'))

# who plays in which seasons
allu={}
for s,d in seasons.items():
    for uid,n in d['users'].items(): allu.setdefault(uid,{'name':n,'seasons':[]})['seasons'].append(s)
print('\nmanagers across the four seasons:')
for uid,v in sorted(allu.items(),key=lambda x:-len(x[1]['seasons'])):
    print(f"  {v['name']:<22} {','.join(v['seasons'])}")
