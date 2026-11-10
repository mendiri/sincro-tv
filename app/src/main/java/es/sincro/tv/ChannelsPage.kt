package es.sincro.tv

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Página «Canales» (en el móvil): a cada canal de Live Guide se le asignan uno o varios canales de tu lista IPTV.
 * La API responde JSON; la página es HTML+JS sin dependencias externas.
 */
object ChannelsPage {
    private val app get() = App.instance

    private fun json(o: Any) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun chJson(c: Channel) = JSONObject().put("n", c.name).put("g", c.group).put("u", c.url)

    private fun ensurePlaylist() {
        if (app.playlist.channels.isEmpty()) app.playlist.ensureLoaded(app.prefs.m3u)
    }

    private fun stations(force: Boolean): List<Station> {
        val guideNames = (GuideCache.load(app.filesDir) ?: emptyList())
            .flatMap { m -> m.channels.mapIndexed { i, n -> n to m.channelIds.getOrElse(i) { "" } } }.distinct()
        val countries = app.prefs.countries.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return Stations.all(app.filesDir, countries, guideNames, force)
    }

    private fun progress() = JSONObject().put("corriendo", AutoAssign.running).put("hechos", AutoAssign.done)
        .put("total", AutoAssign.total).put("asignados", AutoAssign.assigned).put("error", AutoAssign.error ?: "")

    fun api(uri: String, s: NanoHTTPD.IHTTPSession, body: String): Response {
        val q = s.parms
        val p = app.prefs
        return when (uri) {
            "/api/estaciones" -> {
                val list = stations(q["refrescar"] == "1")
                val a = JSONArray()
                for (st in list) a.put(JSONObject().put("r", st.ref).put("n", st.name).put("c", st.country).put("l", st.logo).put("k", p.mappings(st.ref).size))
                json(JSONObject().put("lista", a))
            }
            "/api/asignados" -> json(JSONObject().put("lista", JSONArray(p.mappings(q["s"].orEmpty()).map { chJson(it) })))
            "/api/sugerir" -> {
                ensurePlaylist()
                val st = q["n"].orEmpty()
                val cid = Countries.ofStationName(st) ?: Countries.idOfName(q["c"].orEmpty())
                val idx = app.playlist.index()
                json(JSONObject().put("lista", JSONArray(idx.rank(st, 30, countryId = cid).map { (c, sc) ->
                    chJson(c).put("p", sc).put("otro", cid != null && (Countries.channelIs(c.name, c.group, cid) == false || Countries.mentionsOtherCountry(c.name, cid)))
                })))
            }
            "/api/buscar" -> {
                ensurePlaylist()
                json(JSONObject().put("lista", JSONArray(app.playlist.index().filter(q["q"].orEmpty(), 150).map { chJson(it) })))
            }
            "/api/asignar" -> {
                val o = JSONObject(body)
                val a = o.getJSONArray("canales")
                val list = (0 until a.length()).map { a.getJSONObject(it) }.map { Channel(it.getString("n"), it.optString("g"), it.getString("u")) }
                p.setMappings(o.getString("s"), list)
                json(JSONObject().put("ok", true).put("k", list.size))
            }
            "/api/autoasignar" -> {
                AutoAssign.start { stations(false) }
                json(progress())
            }
            "/api/progreso" -> json(progress())
            "/api/borrartodo" -> {
                p.clearMappings()
                json(JSONObject().put("ok", true))
            }
            "/api/limpiar" -> {
                // Quita de cada canal de Live Guide los canales de tu lista que son de otro país
                var removed = 0
                var touched = 0
                val fixes = LinkedHashMap<String, List<Channel>>()
                for (st in stations(false)) {
                    val cid = Stations.countryOf(st) ?: continue
                    val cur = p.mappings(st.ref)
                    if (cur.isEmpty()) continue
                    val keep = cur.filter { c -> Countries.channelIs(c.name, c.group, cid) != false }
                    if (keep.size != cur.size) { removed += cur.size - keep.size; touched++; fixes[st.ref] = keep }
                }
                for ((n, l) in fixes) p.setMappings(n, l)
                json(JSONObject().put("ok", true).put("quitados", removed).put("canales", touched))
            }
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No existe")
        }
    }

    val HTML = """
<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sincro TV · Canales</title>
<style>
:root{--bg:#0f1a14;--card:#17241d;--card2:#1f3127;--fg:#e8efe9;--muted:#93a39a;--line:#2a3a31;--accent:#3fbf74;--warn:#e5a53a;color-scheme:dark}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.4 system-ui,-apple-system,Roboto,sans-serif}
header{position:sticky;top:0;z-index:5;background:var(--bg);padding:12px 16px;border-bottom:1px solid var(--line);display:grid;gap:8px}
h1{margin:0;font-size:20px}a{color:var(--accent)}
input{width:100%;font:inherit;color:var(--fg);background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 12px}
button{font:inherit;font-weight:600;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:10px;padding:8px 12px;cursor:pointer}
button.pri{background:var(--accent);color:#06140c;border-color:var(--accent)}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}.row>*{min-width:0}
.chips button{padding:6px 10px;font-size:13px}.chips button.on{border-color:var(--accent);color:var(--accent)}
main{padding:8px 16px 40px;display:grid;gap:6px}
h2{font-size:13px;text-transform:uppercase;letter-spacing:.08em;color:var(--muted);margin:14px 0 2px}
.st{display:flex;align-items:center;gap:10px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 10px;cursor:pointer}
.st img{width:44px;height:30px;object-fit:contain;background:#fff;border-radius:6px;flex:none}
.st .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.badge{font-size:12px;font-weight:700;border-radius:999px;padding:2px 8px;background:var(--line);color:var(--muted);flex:none}
.badge.ok{background:var(--accent);color:#06140c}
#panel{position:fixed;inset:0;background:var(--bg);z-index:10;overflow:auto;padding:12px 16px 40px}
#panel[hidden]{display:none}
.item{display:flex;gap:8px;align-items:center;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 10px}
.item .t{flex:1;min-width:0}.item .t b{display:block;overflow-wrap:anywhere}.item .t small{color:var(--muted)}
.item button{padding:6px 10px}
.assigned .item{border-color:var(--accent)}
.hint{color:var(--muted);font-size:13px;margin:4px 0}
#msg{color:var(--warn);font-size:13px;min-height:1em}
</style></head><body>
<header>
  <div class="row"><h1 style="flex:1">Canales</h1><a href="/">← Ajustes</a></div>
  <input id="f" placeholder="Buscar un canal de Live Guide (ej. tve, dazn, sky)">
  <div class="row chips" id="chips">
    <button data-v="all" class="on">Todos</button><button data-v="sin">Sin asignar</button><button data-v="con">Asignados</button>
    <button id="borrar" style="margin-left:auto">Borrar todo</button>
    <button id="limpiar">Quitar canales de otro país</button>
    <button id="auto" class="pri">Asignar automáticamente</button>
  </div>
  <div id="msg"></div>
</header>
<main id="list"><p class="hint">Cargando los canales de Live Guide… La primera vez tarda hasta un minuto (son 87 países); después se guardan.</p></main>

<div id="panel" hidden>
  <div class="row"><button id="back">← Volver</button><h1 id="pt" style="flex:1;font-size:18px"></h1></div>
  <h2>Asignados (en este orden se prueban; ←/→ en la tele para cambiar)</h2>
  <div id="asg" class="assigned" style="display:grid;gap:6px"></div>
  <h2>Sugerencias de tu lista</h2>
  <div id="sug" style="display:grid;gap:6px"><p class="hint">Buscando…</p></div>
  <h2>Buscar en tu lista</h2>
  <input id="q" placeholder="Ej. tve 1, la 1, m+ laliga">
  <div id="res" style="display:grid;gap:6px;margin-top:6px"></div>
</div>

<script>
let all = [], mode = 'all', cur = null, assigned = [];
const ${'$'} = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const norm = s => s.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();

async function load(refresh) {
  const r = await fetch('/api/estaciones' + (refresh ? '?refrescar=1' : ''));
  const j = await r.json();
  if (j.error) { ${'$'}('list').innerHTML = '<p class=hint>No he podido cargar los canales: ' + esc(j.error) + '</p>'; return; }
  all = j.lista; render();
}
function render() {
  const f = norm(${'$'}('f').value.trim());
  const groups = {};
  for (const s of all) {
    if (f && !norm(s.n).includes(f)) continue;
    if (mode === 'sin' && s.k > 0) continue;
    if (mode === 'con' && s.k === 0) continue;
    (groups[s.c] = groups[s.c] || []).push(s);
  }
  const done = all.filter(s => s.k > 0).length;
  let h = '<p class=hint>' + all.length + ' canales de Live Guide · ' + done + ' con canales de tu lista</p>';
  for (const c of Object.keys(groups)) {
    h += '<h2>' + esc(c) + '</h2>';
    for (const s of groups[c]) {
      h += '<div class=st data-r="' + esc(s.r) + '">' + (s.l ? '<img loading=lazy src="' + esc(s.l) + '" alt="">' : '<img alt="">') +
        '<span class=n>' + esc(s.n) + '</span><span class="badge' + (s.k ? ' ok' : '') + '">' + (s.k ? s.k + ' asignados' : 'sin asignar') + '</span></div>';
    }
  }
  ${'$'}('list').innerHTML = h;
}
${'$'}('list').addEventListener('click', e => { const d = e.target.closest('.st'); if (d) open(d.dataset.r); });
${'$'}('f').addEventListener('input', render);
${'$'}('chips').addEventListener('click', e => {
  const b = e.target.closest('button[data-v]'); if (!b) return;
  mode = b.dataset.v; document.querySelectorAll('#chips button[data-v]').forEach(x => x.classList.toggle('on', x === b)); render();
});
${'$'}('auto').addEventListener('click', async () => {
  await fetch('/api/autoasignar', {method:'POST', body:'{}'});
  poll();
});
${'$'}('limpiar').addEventListener('click', async () => {
  ${'$'}('msg').textContent = 'Revisando…';
  const j = await (await fetch('/api/limpiar', {method:'POST', body:'{}'})).json();
  ${'$'}('msg').textContent = j.error ? 'Error: ' + j.error : 'Quitados ' + j.quitados + ' canales de otro país en ' + j.canales + ' canales de Live Guide.';
  load(false);
});
${'$'}('borrar').addEventListener('click', async () => {
  if (!confirm('¿Borrar TODAS las asignaciones de canales? Después puedes volver a pulsar «Asignar automáticamente».')) return;
  await fetch('/api/borrartodo', {method:'POST', body:'{}'});
  ${'$'}('msg').textContent = 'Asignaciones borradas.';
  load(false);
});
async function poll() {
  const j = await (await fetch('/api/progreso')).json();
  if (j.corriendo) {
    ${'$'}('msg').textContent = j.total ? 'Asignando automáticamente: revisados ' + j.hechos + ' de ' + j.total + ' · ' + j.asignados + ' asignados. Puedes cerrar esta página; sigue en la tele.' : 'Preparando…';
    ${'$'}('auto').disabled = true;
    setTimeout(poll, 1500);
  } else {
    ${'$'}('auto').disabled = false;
    if (j.error) ${'$'}('msg').textContent = 'Error: ' + j.error;
    else if (j.total) { ${'$'}('msg').textContent = 'Listo: ' + j.asignados + ' canales asignados automáticamente de ' + j.total + ' revisados. Revísalos y ajusta los que quieras.'; load(false); }
  }
}

function itemHtml(c, i, kind) {
  const isAsg = assigned.some(a => a.u === c.u);
  let btns = '';
  if (kind === 'asg') btns = '<button data-a="up" data-i="' + i + '">↑</button><button data-a="down" data-i="' + i + '">↓</button><button data-a="del" data-i="' + i + '">✕</button>';
  else btns = isAsg ? '<button disabled>✓</button>' : '<button class=pri data-a="add" data-i="' + i + '">Añadir</button>';
  return '<div class=item><div class=t><b>' + esc(c.n) + '</b><small>' + esc(c.g || '') + (c.otro ? ' · <span style="color:var(--warn)">otro país</span>' : '') + '</small></div>' + btns + '</div>';
}
let sug = [], res = [];
function paint() {
  ${'$'}('asg').innerHTML = assigned.length ? assigned.map((c, i) => itemHtml(c, i, 'asg')).join('') : '<p class=hint>Nada asignado todavía. Añade canales de las sugerencias o búscalos.</p>';
  ${'$'}('sug').innerHTML = sug.length ? sug.map((c, i) => itemHtml(c, i, 'sug')).join('') : '<p class=hint>No encuentro nada parecido. Búscalo abajo.</p>';
  ${'$'}('res').innerHTML = res.map((c, i) => itemHtml(c, i, 'res')).join('');
}
async function save() {
  await fetch('/api/asignar', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({s: cur, canales: assigned})});
  const s = all.find(x => x.r === cur); if (s) s.k = assigned.length;
}
async function open(r) {
  const st = all.find(x => x.r === r);
  const n = st ? st.n : r;
  cur = r; ${'$'}('pt').textContent = n + (st && st.c ? ' · ' + st.c : ''); ${'$'}('panel').hidden = false; ${'$'}('q').value = ''; res = []; sug = [];
  assigned = (await (await fetch('/api/asignados?s=' + encodeURIComponent(r))).json()).lista || [];
  paint();
  sug = (await (await fetch('/api/sugerir?n=' + encodeURIComponent(n) + '&c=' + encodeURIComponent(st ? st.c : ''))).json()).lista || [];
  paint();
}
${'$'}('panel').addEventListener('click', async e => {
  const b = e.target.closest('button[data-a]'); if (!b) return;
  const i = +b.dataset.i, a = b.dataset.a, sec = b.closest('#sug') ? sug : res;
  if (a === 'add') assigned.push(sec[i]);
  if (a === 'del') assigned.splice(i, 1);
  if (a === 'up' && i > 0) [assigned[i-1], assigned[i]] = [assigned[i], assigned[i-1]];
  if (a === 'down' && i < assigned.length - 1) [assigned[i+1], assigned[i]] = [assigned[i], assigned[i+1]];
  paint(); await save();
});
${'$'}('back').addEventListener('click', () => { ${'$'}('panel').hidden = true; render(); });
let t = null;
${'$'}('q').addEventListener('input', () => {
  clearTimeout(t);
  t = setTimeout(async () => {
    const v = ${'$'}('q').value.trim(); if (!v) { res = []; paint(); return; }
    res = (await (await fetch('/api/buscar?q=' + encodeURIComponent(v))).json()).lista || []; paint();
  }, 300);
});
load(false);
poll();
</script></body></html>
""".trimIndent()
}
