import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { IptvGroupSettings } from '../../components/settings/IptvGroupSettings';
import type { AppSettings, IptvChannel } from '../../lib/types';

function Fixture() {
  const [settings, setSettings] = useState(() => JSON.parse(localStorage.getItem('groups') || 'null') || {
    iptvPlaylists: [{id:'p',name:'Main playlist'},{id:'other',name:'Other playlist'}], groupOrder:[], hiddenGroupIds:[]
  });
  const channels = ['News','Sports','Movies','A very long category name that should fit on a small phone'].map((group,index)=>({id:`p:${index}`,group}));
  channels.push({id:'other:1',group:'News'});
  return <main className="settings-panel-card" style={{maxWidth:800,margin:'20px auto',padding:12}}>
    <IptvGroupSettings channels={channels as IptvChannel[]} settings={settings as AppSettings} updateSettings={patch=>setSettings((previous: AppSettings)=>{
      const next={...previous,...patch}; localStorage.setItem('groups',JSON.stringify(next)); return next;
    })}/>
  </main>;
}
createRoot(document.getElementById('root')!).render(<Fixture/>);
