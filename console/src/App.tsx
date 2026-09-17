import { useState } from 'react';
import { ArrowUpRight, Database, Search, ShieldCheck } from 'lucide-react';
import { SearchWorkspace } from './SearchWorkspace';
import { FeedWorkspace } from './FeedWorkspace';

export function App() {
  const [view, setView] = useState('search');
  return <>
    <a className="skip" href="#workspace">Skip to workspace</a>
    <header className="masthead"><a className="brand" href="#"><ShieldCheck aria-hidden="true" size={25} /><span>verified<span className="brand-light">offers</span><small>INVESTIGATION CONSOLE</small></span></a><span className="environment">LOCAL / SYNTHETIC DATA</span></header>
    <div className="shell"><aside className="sidebar"><p className="eyebrow">WORKSPACE</p><nav aria-label="Workspace"><button aria-current={view === 'search' ? 'page' : undefined} onClick={() => setView('search')}><Search size={18} />Verified search</button><button aria-current={view === 'feeds' ? 'page' : undefined} onClick={() => setView('feeds')}><Database size={18} />Merchant feeds</button></nav><div className="sidebar-note"><span className="eyebrow">THE TRUST BOUNDARY</span><p>Search finds candidates.<br />Source facts decide.</p><span>No checkout or stock guarantee.</span></div></aside>
    <main id="workspace"><div className="page-heading"><div><p className="eyebrow">CATALOG / EVIDENCE</p><h1>{view === 'search' ? 'Verified search' : 'Merchant feeds'}</h1><p>Inspect the facts behind an offer, not just its place in the index.</p></div><ArrowUpRight size={28} aria-hidden="true" /></div>
    <div className="scope-note">Local demo only. Tenant fields select data; they do not authenticate you.</div>
    {view === 'search' && <SearchWorkspace />}
    <div hidden={view !== 'feeds'}><FeedWorkspace /></div>
    </main></div><footer>Verified Offers <span>Local reference implementation · No production-readiness claim</span></footer>
  </>;
}
