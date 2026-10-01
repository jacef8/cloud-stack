import json,urllib.request,ssl
ctx=ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
def get(u):
    try:
        r=urllib.request.Request(u,headers={'User-Agent':'record-book/1.0'})
        with urllib.request.urlopen(r,timeout=30,context=ctx) as f: return json.load(f)
    except Exception: return None
lid='1219702278403932160';out=[]
while lid:
    L=get(f'https://api.sleeper.app/v1/league/{lid}')
    if not L: break
    # does it actually have played games?
    played=[]
    for w in (1,5,10,14):
        m=get(f'https://api.sleeper.app/v1/league/{lid}/matchups/{w}')
        if m and any((x.get('points') or 0)>0 for x in m): played.append(w)
    out.append({'id':lid,'season':L['season'],'name':L['name'].strip(),'status':L['status'],
        'teams':L['settings']['num_teams'],'pw':L['settings'].get('playoff_week_start'),
        'sampled_weeks_with_scores':played})
    lid=L.get('previous_league_id')
for o in out: print(json.dumps(o))
json.dump(out,open('chain2.json','w'))
