import json,urllib.request,ssl
ctx=ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
def get(u):
    try:
        r=urllib.request.Request(u,headers={'User-Agent':'record-book/1.0'})
        with urllib.request.urlopen(r,timeout=30,context=ctx) as f: return json.load(f)
    except Exception: return None
LEAGUES=[('2023','1001022922321977344'),('2024','1124845472708591616'),
         ('2025','1219702278403932160'),('2026','1325921258503667712')]
S=json.load(open('seasons.json'))
name={}
for s,_ in LEAGUES:
    for uid,n in S[s]['users'].items(): name[uid]=n
teams={}
for season,lid in LEAGUES:
    us=get(f'https://api.sleeper.app/v1/league/{lid}/users') or []
    for u in us:
        uid=u['user_id']; md=u.get('metadata') or {}
        tn=(md.get('team_name') or '').strip()
        nm=name.get(uid,u.get('display_name'))
        if nm: teams.setdefault(nm,{})[season]=tn or None
print(json.dumps(teams,indent=1,ensure_ascii=False))
json.dump(teams,open('teams.json','w'))
