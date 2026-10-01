#!/usr/bin/env node
/* Builds the app icons. Full bleed and opaque at every size: the phone cuts
   its own shape, so drawing our own rounded corners would leave blank ones
   after masking. The symbol stays inside the middle 66% so no mask clips it. */
const { chromium } = require('playwright');
const path=require('path');
const OUT=path.resolve(__dirname,'../../public');

const PAGE=size=>`<!doctype html><meta charset="utf-8">
<style>html,body{margin:0;background:#05100D}
.ico{width:${size}px;height:${size}px;position:relative;overflow:hidden}
.bg{position:absolute;inset:0;background:
  radial-gradient(120% 120% at 26% 14%,#1B4035 0%,#0B1A16 58%,#05100D 100%)}
.sym{position:absolute;inset:17%}
svg{width:100%;height:100%;display:block}
.g{fill:none;stroke:url(#grad);stroke-width:9;stroke-linecap:round;stroke-linejoin:round}
</style>
<div class="ico"><div class="bg"></div><div class="sym">
<svg viewBox="0 0 120 120">
  <defs><linearGradient id="grad" gradientUnits="userSpaceOnUse" x1="24" y1="100" x2="96" y2="20">
    <stop offset="0%" stop-color="#00D9FF"/><stop offset="55%" stop-color="#00E8A0"/>
    <stop offset="100%" stop-color="#B6FF3D"/></linearGradient></defs>
  <path class="g" d="M41 20h38v24c0 10.5-8.5 19-19 19s-19-8.5-19-19V20z"/>
  <path class="g" d="M41 27H28c0 13 5.5 20 13 22.5"/>
  <path class="g" d="M79 27h13c0 13-5.5 20-13 22.5"/>
  <path class="g" d="M60 62v18"/>
  <path class="g" d="M44 98h32l-4-18h-24z"/>
</svg></div></div>`;

(async()=>{
  const b=await chromium.launch();
  for(const [file,size] of [['icon-512.png',512],['icon-192.png',192],['apple-touch-icon.png',180]]){
    const p=await b.newPage({viewport:{width:size,height:size},deviceScaleFactor:1});
    await p.setContent(PAGE(size),{waitUntil:'load'});
    await p.waitForTimeout(150);
    const el=await p.$('.ico');
    await el.screenshot({path:path.join(OUT,file)});   // opaque: omitBackground is never set
    await p.close();
    console.log('wrote '+file+' at '+size);
  }
  await b.close();
})();
