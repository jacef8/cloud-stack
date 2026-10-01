import json,urllib.request,os,ssl
ctx=ssl.create_default_context(cafile='/root/.ccr/ca-bundle.crt')
def get(u):
    r=urllib.request.Request(u,headers={'User-Agent':'record-book/1.0'})
    with urllib.request.urlopen(r,timeout=30,context=ctx) as f: return json.load(f)
lid='1325921258503667712';chain=[]
while lid:
    L=get(f'https://api.sleeper.app/v1/league/{lid}')
    chain.append({'id':lid,'season':L['season'],'name':L['name'].strip(),
        'teams':L['settings']['num_teams'],'pw_start':L['settings'].get('playoff_week_start'),
        'leg':L['settings'].get('leg'),'status':L['status']})
    lid=L.get('previous_league_id')
print(json.dumps(chain,indent=1))
json.dump(chain,open('chain.json','w'))
