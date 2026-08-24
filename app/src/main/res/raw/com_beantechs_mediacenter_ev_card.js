/*
 * Card de carga na Home da MediaCenter (com.beantechs.mediacenter).
 *
 * Irmão do card de clima do haval-climate-control: mesma moldura (536x300, fundo
 * em degradê do OEM), desenhada num Bitmap por Canvas, no mesmo
 * online_music_container. Este fica no slot 1 — à direita do card de clima.
 *
 * Origem do desenho: preview/home-card-preview.html (aprovado em tela).
 *
 * ── Convivência com o card de clima ────────────────────────────────────────
 * Os dois apps injetam agentes Frida distintos no MESMO processo. O protocolo é
 * todo baseado nas VIEWS — o processo da MediaCenter não escreve em
 * /data/local/tmp, então nada de arquivo compartilhado:
 *   - ninguém chama removeAllViews(): a limpeza tira só o que NÃO é card
 *     conhecido (clearForeign). Sem isso os dois se apagam em looping a cada tick.
 *   - a ordem na fileira sai de CARD_TAGS, não de quem pintou primeiro.
 *   - dono = card de menor slot presente na fileira; só o dono mexe no que é
 *     global (título "Mídia online", topMargin da fileira, lista de CPs, restore).
 *     Com o clima ativo, quem manda é ele; sozinho, este script assume.
 *
 * ── Dados ──────────────────────────────────────────────────────────────────
 * Diferente do clima, os valores NÃO vêm do PlatformAdapterClient: as chaves
 * car.ev_* são lidas pelo EvManagerService via IIntelligentVehicleControlService,
 * e fase/ciclo automático só existem no app (SharedPreferences). O serviço grava
 * tudo em /data/local/tmp/haval_ev_home_card, numa linha:
 *
 *   on;conn=1;auto=1;phase=80;soc=62;km=142;target=80
 *
 * "off" (ou arquivo ausente) -> restaura a fileira e sai.
 *
 * Base de engenharia reversa: haval-engine-reverse/docs/HANDOFF-cards-midia-online.md
 */
"use strict";

function log(m) { console.log("[hem-card] " + m); }

Java.perform(function () {

    var CTRL = "/data/local/tmp/haval_ev_home_card";
    var ACT  = "com.beantechs.mediacenter.mainmodel1xos.ui.MediaCenterActivity";
    var IMPL_CLS = "com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl";
    var OUR_PKG  = "br.com.redesurftank.havalevmanager";
    var CARD_TAG = "hem-home-card";
    var CP_CARD  = 591;          // CP virtual: precisa cair em 501..600 p/ o onClick do OEM
    var ROW_TOP  = 100;          // topMargin da fileira com o titulo escondido

    var CARD_TAGS = ["hcc-home-card", "hem-home-card"];   // clima, ev-manager
    var SLOT      = 1;
    var GAP       = 24;          // respiro entre os cards: 536 + 24 + 536 = 1096 < 1158
    var ROW_TOP_DEFAULT = 168;   // topMargin de fábrica da fileira, medido no APK

    // ── wrappers ───────────────────────────────────────────────────────────
    // Regra do handoff §3: instância de Java.choose expõe só os métodos da
    // própria classe. Todo método herdado (findViewById, setVisibility, ...)
    // vai por Java.cast para a classe que o DECLARA.
    var JFile      = Java.use("java.io.File");
    var FileReader = Java.use("java.io.FileReader");
    var BufReader  = Java.use("java.io.BufferedReader");
    var Integer    = Java.use("java.lang.Integer");
    var Str        = Java.use("java.lang.String");

    var ActivityCls  = Java.use("android.app.Activity");
    var ViewCls      = Java.use("android.view.View");
    var ViewGroupCls = Java.use("android.view.ViewGroup");
    var VGLayoutParams = Java.use("android.view.ViewGroup$LayoutParams");
    var MarginLP       = Java.use("android.view.ViewGroup$MarginLayoutParams");
    var ImageView    = Java.use("android.widget.ImageView");
    var LLParams     = Java.use("android.widget.LinearLayout$LayoutParams");

    var Bitmap    = Java.use("android.graphics.Bitmap");
    var BmpConfig = Java.use("android.graphics.Bitmap$Config");
    var Canvas    = Java.use("android.graphics.Canvas");
    var Paint     = Java.use("android.graphics.Paint");
    var PStyle    = Java.use("android.graphics.Paint$Style");
    var PCap      = Java.use("android.graphics.Paint$Cap");
    var PJoin     = Java.use("android.graphics.Paint$Join");
    var Path      = Java.use("android.graphics.Path");
    var RectF     = Java.use("android.graphics.RectF");
    var Typeface  = Java.use("android.graphics.Typeface");
    var LinGrad   = Java.use("android.graphics.LinearGradient");
    var TileMode  = Java.use("android.graphics.Shader$TileMode");

    var loggedOnce = {};
    function logOnce(key, msg) {
        if (loggedOnce[key]) return;
        loggedOnce[key] = true;
        log(msg);
    }

    // ── paleta (idêntica ao HmiTheme do app e ao card de clima) ─────────────
    // `|0` converte para int com sinal: 0xFF141414 estoura o range de int Java
    // e o marshaller do Frida rejeita o número sem a conversão.
    var G_BG_COLORS   = [0xD12D2F33 | 0, 0xD1242627 | 0, 0xD1212325 | 0, 0xD11E2021 | 0];
    var G_BG_STOPS    = [0.0, 0.22, 0.52, 1.0];
    var G_EDGE_COLORS = [0xDC535557 | 0, 0xDC4B4D4F | 0, 0xDC3C3E40 | 0, 0xDC2C2D31 | 0];
    var G_EDGE_STOPS  = [0.0, 0.22, 0.55, 1.0];
    var C_SURFACE2    = 0xFF1C1C1C | 0;
    var C_FG          = 0xFFFAFAFA | 0;
    var C_MUTED       = 0xFFA3A3A3 | 0;
    var C_DIM         = 0xFF6B6B6B | 0;
    var C_FAINT       = 0xFF404040 | 0;
    var C_ACCENT      = 0xFF22C55E | 0;   // fase CARGA
    var C_ACCENT_SOFT = 0x1F22C55E | 0;
    var C_ACCENT_EDGE = 0x6622C55E | 0;
    var C_AMBER       = 0xFFF59E0B | 0;   // fase USO
    var C_AMBER_SOFT  = 0x1FF59E0B | 0;
    var C_AMBER_EDGE  = 0x66F59E0B | 0;
    var C_RED         = 0xFFEF4444 | 0;   // bateria <= 20%
    var C_BORDER      = 0x1AFFFFFF | 0;

    // ── geometria (densidade 1.0 nesta tela: 1dp = 1px) ─────────────────────
    var W = 536, H = 300, PAD = 32;

    // ── leitura do estado (escrito pelo EvManagerService) ───────────────────
    /** Snapshot vazio = card desligado / app sem dados. */
    function emptyState() {
        return { on: false, conn: false, auto: false, phase: null,
                 soc: null, km: null, target: null };
    }

    function parseCtrl(line) {
        var s = emptyState();
        if (line === null) return s;
        var parts = ("" + line).trim().toLowerCase().split(";");
        if (parts.length === 0 || parts[0].trim() !== "on") return s;
        s.on = true;
        for (var i = 1; i < parts.length; i++) {
            var kv = parts[i].split("=");
            if (kv.length !== 2) continue;
            var k = kv[0].trim(), v = kv[1].trim();
            if (v === "" || v === "--") continue;
            var n = parseInt(v, 10);
            if (k === "conn")        s.conn   = (v === "1");
            else if (k === "auto")   s.auto   = (v === "1");
            else if (k === "phase")  s.phase  = isNaN(n) ? null : n;
            else if (k === "soc")    s.soc    = isNaN(n) ? null : n;
            else if (k === "km")     s.km     = isNaN(n) ? null : n;
            else if (k === "target") s.target = isNaN(n) ? null : n;
        }
        return s;
    }

    function readCtrl() {
        try {
            var f = JFile.$new(CTRL);
            if (!f.exists()) return emptyState();
            var br = BufReader.$new(FileReader.$new(f));
            var l  = br.readLine();
            br.close();
            return parseCtrl(l);
        } catch (e) {
            log("readCtrl err: " + e);
            return emptyState();
        }
    }

    function stateSig(s) {
        return [s.on, s.conn, s.auto, s.phase, s.soc, s.km, s.target].join("|");
    }

    // ── desenho ─────────────────────────────────────────────────────────────
    function newPaint(color, stroke, strokeW) {
        var p = Paint.$new.overload().call(Paint);
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(stroke ? PStyle.STROKE.value : PStyle.FILL.value);
        if (strokeW) {
            p.setStrokeWidth(strokeW);
            p.setStrokeCap(PCap.ROUND.value);
            p.setStrokeJoin(PJoin.ROUND.value);
        }
        return p;
    }

    /** Paint com LinearGradient no eixo (0,0)->(W,H) — o mesmo do fundo do OEM. */
    function gradPaint(colors, stops, strokeW) {
        var p = Paint.$new.overload().call(Paint);
        p.setAntiAlias(true);
        p.setStyle(strokeW ? PStyle.STROKE.value : PStyle.FILL.value);
        if (strokeW) p.setStrokeWidth(strokeW);
        p.setShader(LinGrad.$new.overload("float", "float", "float", "float",
            "[I", "[F", "android.graphics.Shader$TileMode")
            .call(LinGrad, 0.0, 0.0, W, H,
                Java.array("int", colors), Java.array("float", stops),
                TileMode.CLAMP.value));
        return p;
    }

    function textPaint(color, size, weightName, spacing) {
        var p = newPaint(color, false, 0);
        p.setTextSize(size);
        p.setTypeface(Typeface.create.overload("java.lang.String", "int")
            .call(Typeface, weightName, 0));
        if (spacing) p.setLetterSpacing(spacing);
        return p;
    }

    function measure(paint, s) {
        return paint.measureText.overload("java.lang.String").call(paint, s);
    }

    function roundRect(cv, l, t, r, b, rad, paint) {
        cv.drawRoundRect.overload("android.graphics.RectF", "float", "float",
            "android.graphics.Paint")
            .call(cv, RectF.$new.overload("float", "float", "float", "float")
                .call(RectF, l, t, r, b), rad, rad, paint);
    }

    function rect(cv, l, t, r, b, paint) {
        cv.drawRect.overload("float", "float", "float", "float", "android.graphics.Paint")
            .call(cv, l, t, r, b, paint);
    }

    function circle(cv, cx, cy, r, paint) {
        cv.drawCircle.overload("float", "float", "float", "android.graphics.Paint")
            .call(cv, cx, cy, r, paint);
    }

    function text(cv, s, x, y, paint) {
        cv.drawText.overload("java.lang.String", "float", "float", "android.graphics.Paint")
            .call(cv, s, x, y, paint);
    }

    /** Raio: polígono fechado, mesma silhueta do ícone do preview. */
    function drawBolt(cv, cx, cy, h, color) {
        var u = h / 20.0;                      // 20 unidades de altura no desenho base
        var pts = [[1.5, -10], [-4.5, 1.5], [1.0, 1.5], [-0.5, 10], [5.5, -1.5], [0.0, -1.5]];
        var path = Path.$new();
        for (var i = 0; i < pts.length; i++) {
            var x = cx + pts[i][0] * u, y = cy + pts[i][1] * u;
            if (i === 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        cv.drawPath.overload("android.graphics.Path", "android.graphics.Paint")
            .call(cv, path, newPaint(color, false, 0));
    }

    /** Pilha de bateria deitada, com o miolo preenchido conforme `pct`. */
    function drawBattery(cv, cx, cy, color, pct) {
        var w = 44, h = 30, x = cx - w / 2, y = cy - h / 2;
        roundRect(cv, x + 1.2, y + 1.2, x + w - 8, y + h - 1.2, 6, newPaint(color, true, 2.4));
        roundRect(cv, x + w - 6, cy - 5.5, x + w - 1.5, cy + 5.5, 2.5, newPaint(color, false, 0));
        var innerL = x + 5, innerR = x + w - 12;
        var fill   = (pct === null) ? 0 : Math.max(0, Math.min(100, pct)) / 100.0;
        if (fill > 0) {
            roundRect(cv, innerL, y + 6, innerL + (innerR - innerL) * fill, y + h - 6, 3,
                newPaint(color, false, 0));
        }
    }

    /**
     * Pinta o card inteiro num Bitmap ARGB_8888 de 536x300. Só é chamado quando o
     * snapshot muda (ver tick) — redesenhar a cada 1,5s geraria ~640 KB de lixo
     * por tick sem necessidade.
     */
    function drawCard(s) {
        var bmp = Bitmap.createBitmap.overload("int", "int", "android.graphics.Bitmap$Config")
            .call(Bitmap, W, H, BmpConfig.ARGB_8888.value);
        var cv = Canvas.$new.overload("android.graphics.Bitmap").call(Canvas, bmp);

        // fundo + borda — degradê do OEM (mesmo eixo nos dois), raio 24 nosso
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_BG_COLORS, G_BG_STOPS, 0));
        roundRect(cv, 0.5, 0.5, W - 0.5, H - 0.5, 24, gradPaint(G_EDGE_COLORS, G_EDGE_STOPS, 1));

        var conn     = s.conn;
        var autoOn   = conn && s.auto;
        var charging = autoOn && s.phase === 80;
        var using    = autoOn && s.phase === 20;
        var accent   = charging ? C_ACCENT : using ? C_AMBER : C_MUTED;

        // ── cabeçalho: rótulo + selo do ciclo ──
        text(cv, "CARGA", PAD, 52, textPaint(C_DIM, 17, "sans-serif-medium", 0.18));

        var badge  = !conn ? "OFF" : s.auto ? "AUTO" : "MANUAL";
        var badgeP = textPaint(autoOn ? C_ACCENT : C_FAINT, 16, "monospace", 0.06);
        var badgeW = measure(badgeP, badge);
        text(cv, badge, W - PAD - badgeW, 52, badgeP);
        circle(cv, W - PAD - badgeW - 16, 46, 5,
            newPaint(autoOn ? C_ACCENT : C_FAINT, false, 0));

        // ── meio: tile + nível da bateria ──
        var tileX = PAD, tileY = 98, tileS = 88;
        roundRect(cv, tileX, tileY, tileX + tileS, tileY + tileS, 22,
            newPaint(charging ? C_ACCENT_SOFT : using ? C_AMBER_SOFT : C_SURFACE2, false, 0));
        roundRect(cv, tileX + 0.5, tileY + 0.5, tileX + tileS - 0.5, tileY + tileS - 0.5, 22,
            newPaint(charging ? C_ACCENT_EDGE : using ? C_AMBER_EDGE : C_BORDER, true, 1));
        var iconColor = !conn ? C_FAINT : accent;
        if (charging) drawBolt(cv, tileX + tileS / 2, tileY + tileS / 2, 46, iconColor);
        else          drawBattery(cv, tileX + tileS / 2, tileY + tileS / 2, iconColor,
                                  conn ? s.soc : null);

        var sx     = tileX + tileS + 24;
        var socStr = (conn && s.soc !== null) ? ("" + s.soc) : "--";
        var socP   = textPaint(conn ? C_FG : C_FAINT, 76, "sans-serif-medium", 0);
        text(cv, socStr, sx, 170, socP);
        if (conn && s.soc !== null) {
            text(cv, "%", sx + measure(socP, socStr) + 6, 170,
                textPaint(C_MUTED, 34, "sans-serif", 0));
        }
        text(cv, "BATERIA", sx + 2, 198, textPaint(C_DIM, 15, "sans-serif-medium", 0.2));

        // ── coluna da direita: pastilha da fase + autonomia elétrica ──
        var pillTxt = charging ? "CARGA" : using ? "USO" : "--";
        var pillP   = textPaint(!autoOn ? C_FAINT : accent, 19, "monospace", 0.1);
        var pillW   = measure(pillP, pillTxt) + 32;
        var pillR   = W - PAD, pillL = pillR - pillW, pillT = 104, pillB = 146;
        roundRect(cv, pillL, pillT, pillR, pillB, 14,
            newPaint(charging ? C_ACCENT_SOFT : using ? C_AMBER_SOFT : C_SURFACE2, false, 0));
        roundRect(cv, pillL + 0.5, pillT + 0.5, pillR - 0.5, pillB - 0.5, 14,
            newPaint(charging ? C_ACCENT_EDGE : using ? C_AMBER_EDGE : C_BORDER, true, 1));
        text(cv, pillTxt, pillL + 16, pillB - 14, pillP);

        var unitP = textPaint(C_DIM, 13, "sans-serif-medium", 0.1);
        var unitW = measure(unitP, "km EV");
        var kmStr = (conn && s.km !== null) ? ("" + s.km) : "--";
        var kmP   = textPaint(conn ? C_MUTED : C_FAINT, 19, "monospace", 0);
        var kmW   = measure(kmP, kmStr);
        text(cv, kmStr,   W - PAD - unitW - 6 - kmW, 182, kmP);
        text(cv, "km EV", W - PAD - unitW,           182, unitP);

        // ── rodapé: barra de SOC com o traço da meta ──
        var barL = PAD, barR = W - PAD, barT = 236, barB = 250;
        roundRect(cv, barL, barT, barR, barB, 7, newPaint(C_SURFACE2, false, 0));
        roundRect(cv, barL + 0.5, barT + 0.5, barR - 0.5, barB - 0.5, 7,
            newPaint(C_BORDER, true, 1));
        if (conn && s.soc !== null && s.soc > 0) {
            var lvl  = Math.max(0, Math.min(100, s.soc)) / 100.0;
            var fillC = charging ? C_ACCENT
                      : (s.soc <= 20) ? C_RED
                      : using ? C_AMBER : C_MUTED;
            var fillR = barL + (barR - barL) * lvl;
            roundRect(cv, barL, barT, Math.max(barL + 14, fillR), barB, 7,
                newPaint(fillC, false, 0));
        }
        var target = conn ? (autoOn ? s.phase : s.target) : null;
        if (target !== null) {
            var mx = barL + (barR - barL) * (Math.max(0, Math.min(100, target)) / 100.0);
            mx = Math.max(barL + 1.5, Math.min(barR - 1.5, mx));
            roundRect(cv, mx - 1.5, barT - 5, mx + 1.5, barB + 5, 2,
                newPaint(C_FG, false, 0));
        }

        // escala + meta/gatilho
        var scaleP = textPaint(C_DIM, 13, "monospace", 0.1);
        text(cv, "0", barL, 274, scaleP);
        var hundred = "100";
        text(cv, hundred, barR - measure(scaleP, hundred), 274, scaleP);

        var mid = (target === null) ? "META --"
                : ("META " + target + "%" + (charging ? "  ·  vira em >=75"
                                          : using    ? "  ·  vira em <=20" : ""));
        var midP = textPaint(conn ? C_MUTED : C_FAINT, 13, "monospace", 0.1);
        text(cv, mid, (W - measure(midP, mid)) / 2, 274, midP);

        return bmp;
    }

    // ── ids de recurso (§3: campo estático de R$id, com fallback do arsc) ────
    var RID = null;
    try {
        RID = Java.use("com.beantechs.mediacenter.mainmodel1xos.R$id");
    } catch (e) {
        log("R$id indisponível, usando ids fixos: " + e);
    }
    var RID_FIXO = {
        online_music: 0x7f0a01ba,
        horizontalScrollView: 0x7f0a0107,
        online_music_container: 0x7f0a01bb
    };
    function resId(name) {
        try {
            if (RID !== null && RID[name] !== undefined) return RID[name].value;
        } catch (e) { /* cai no fixo */ }
        return RID_FIXO[name];
    }

    function viewOf(act, name) {
        var id = resId(name);
        if (!id) { log("id " + name + " desconhecido"); return null; }
        try {
            return Java.cast(act, ActivityCls).findViewById(id);
        } catch (e) {
            log("findViewById(" + name + ") err: " + e);
            return null;
        }
    }

    // ── estado desejado ─────────────────────────────────────────────────────
    var enabled    = false;
    var lastCtrl   = null;      // marcador "ainda não li"
    var lastSig    = null;      // snapshot já pintado
    var origList   = null;      // CPs de fábrica, para restaurar no "off"
    var origRowTop = null;      // topMargin original da fileira, para restaurar no "off"

    // Integer[] no Frida é array JS de wrappers Integer (§7.2): montar via
    // java.lang.reflect.Array devolve wrapper de Object e o marshaller rejeita.
    function buildArr(list) {
        var out = [];
        for (var i = 0; i < list.length; i++) out.push(Integer.valueOf(list[i]));
        return out;
    }

    /** Lista de CPs que a tela deve enxergar: vazia com o card ligado. */
    function wantedList() {
        return enabled ? [] : (origList === null ? [] : origList);
    }

    /**
     * Captura a lista de fábrica uma única vez, antes de qualquer sobrescrita.
     * Se o card de clima já zerou a fileira, a captura vem vazia — daí o
     * fallback para a lista conhecida do Brasil (handoff §9).
     */
    var DEFAULT_CPS = [551];
    function captureOriginal() {
        if (origList !== null) return;
        try {
            var inst = Java.use("com.beantechs.mediacenter.mediacentermodel.MediaCenterManager")
                .getInstance();
            if (inst === null) return;               // serviço ainda não subiu; tenta no próximo tick
            var cur = inst.getCPList();
            var acc = [];
            if (cur !== null) for (var i = 0; i < cur.length; i++) acc.push(parseInt("" + cur[i], 10));
            if (acc.length === 0) {
                origList = DEFAULT_CPS.slice();
                log("CPs de fábrica vieram vazios (card vizinho ativo?) — usando ["
                    + origList.join(",") + "]");
            } else {
                origList = acc;
                log("CPs de fábrica = [" + acc.join(",") + "]");
            }
        } catch (e) {
            log("captureOriginal err: " + e);
        }
    }

    // ── hooks da cadeia da lista de CPs ─────────────────────────────────────
    var CHAIN = [
        ["com.beantechs.mediacenter.mediacentermodel.MediaCenterManager", "getCPList"],
        ["com.beantechs.mediacenter.mediacentermodel.core.OnlineOsImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getCPList"],
        ["com.beantechs.mediacenter.core_onlineosmodel.OnlineOsModelImpl", "getMCpList"]
    ];
    CHAIN.forEach(function (pair) {
        var cls = pair[0], name = pair[1];
        try {
            var w = Java.use(cls);
            if (w[name] === undefined) { log("SEM método " + cls + "." + name); return; }
            // Com o card do clima injetado, o hook dele já está na cadeia: quando
            // este aqui está desligado, this[name]() cai lá e a resposta continua
            // certa para os dois.
            w[name].implementation = function () {
                if (!enabled) return this[name]();
                try { return buildArr([]); }
                catch (e) { log("buildArr err: " + e); return this[name](); }
            };
            log("hook OK " + cls.split(".").pop() + "." + name);
        } catch (e) {
            log("hook FALHOU " + cls + "." + name + ": " + e);
        }
    });

    /** Escreve a lista direto nas instâncias vivas (argumento, não retorno — §7.2). */
    function applyToInstances() {
        var n = 0;
        try {
            var arr = buildArr(wantedList());
            Java.choose(IMPL_CLS, {
                onMatch: function (inst) {
                    try { inst.setMCpList(arr); n++; }
                    catch (e) { log("setMCpList err: " + e); }
                },
                onComplete: function () {}
            });
        } catch (e) {
            log("applyToInstances err: " + e);
        }
        log("setMCpList aplicado em " + n + " instância(s)");
        return n;
    }

    // ── clique: reusa o dispatch por elevation do OEM (handoff §5.3) ─────────
    function openOurApp() {
        try {
            var ctx = Java.use("android.app.ActivityThread")
                .currentApplication().getApplicationContext();
            var i = ctx.getPackageManager().getLaunchIntentForPackage(OUR_PKG);
            if (i === null) { log("app " + OUR_PKG + " não instalado"); return false; }
            i.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(i);
            log("card clicado -> abrindo " + OUR_PKG);
            return true;
        } catch (e) {
            log("openOurApp err: " + e);
            return false;
        }
    }

    try {
        Java.use(IMPL_CLS).skipApp4OnlineOs.implementation = function (cp) {
            if (parseInt("" + cp, 10) === CP_CARD && openOurApp()) return;
            return this.skipApp4OnlineOs(cp);   // pode cair no hook do card de clima
        };
        log("hook OK skipApp4OnlineOs (clique do card)");
    } catch (e) {
        log("hook FALHOU skipApp4OnlineOs: " + e);
    }

    /**
     * Hook no próprio loadOnlineMusicCard: ele roda no onCreate e monta a fileira
     * do OEM. Trocando o conteúdo AQUI, dentro da mesma chamada na main thread, o
     * card entra no mesmo frame — sem a janela de até 1,5s com a fileira do OEM.
     *
     * `this` de hook é referência JNI LOCAL (handoff §7.4): usar no mesmo tick é
     * correto, guardar entre ticks aborta o processo. Aqui não guardamos nada.
     */
    try {
        Java.use(ACT).loadOnlineMusicCard.implementation = function () {
            this.loadOnlineMusicCard();
            if (!enabled) return;
            try {
                paintCard(this, readCtrl());
            } catch (e) {
                logOnce("onload:" + e, "paint no loadOnlineMusicCard err: " + e);
            }
        };
        log("hook OK loadOnlineMusicCard (card no mesmo frame do onCreate)");
    } catch (e) {
        log("hook FALHOU loadOnlineMusicCard: " + e);
    }

    // ── convivência entre cards (ver comentário no topo) ────────────────────
    /** Posição do card `tag` na ordem canônica da fileira; -1 se não é card nosso. */
    function slotOf(tag) {
        return (tag === null) ? -1 : CARD_TAGS.indexOf(tag);
    }

    function childTag(box, i) {
        try {
            var t = Java.cast(box.getChildAt(i), ViewCls).getTag();
            return (t === null) ? null : ("" + t);
        } catch (e) {
            return null;
        }
    }

    /**
     * Tira da fileira tudo que não é card conhecido — os ícones do OEM saem, o
     * card do app parceiro fica. É o que substitui o removeAllViews().
     */
    function clearForeign(box) {
        for (var i = box.getChildCount() - 1; i >= 0; i--) {
            if (slotOf(childTag(box, i)) >= 0) continue;
            try { box.removeViewAt(i); }
            catch (e) { logOnce("rmv:" + e, "removeViewAt err: " + e); }
        }
    }

    /** Índice de inserção que respeita a ordem de CARD_TAGS. */
    function insertIndex(box) {
        var idx = 0;
        for (var i = 0; i < box.getChildCount(); i++) {
            var s = slotOf(childTag(box, i));
            if (s >= 0 && s < SLOT) idx = i + 1;
        }
        return idx;
    }

    /** Dono = nenhum card de slot menor na fileira. Vale por tick, não se guarda. */
    function isOwner(box) {
        for (var i = 0; i < box.getChildCount(); i++) {
            var s = slotOf(childTag(box, i));
            if (s >= 0 && s < SLOT) return false;
        }
        return true;
    }

    /** true se o card de OUTRO app está na fileira agora. */
    function peerPresent(box) {
        for (var i = 0; i < box.getChildCount(); i++) {
            var t = childTag(box, i);
            if (t !== null && t !== CARD_TAG && slotOf(t) >= 0) return true;
        }
        return false;
    }

    // ── montagem da view ────────────────────────────────────────────────────
    /**
     * Cria (ou reaproveita) o ImageView do card no online_music_container e
     * pinta o bitmap. Roda SEMPRE na main thread. A view é achada por tag —
     * guardar o wrapper entre ticks derruba o processo (§7.4).
     */
    function paintCard(act, s) {
        try {
            var boxRaw = viewOf(act, "online_music_container");
            if (boxRaw === null) { logOnce("nobox", "container não está inflado"); return; }

            var tagStr = Str.$new(CARD_TAG);
            var boxV   = Java.cast(boxRaw, ViewCls);
            var box    = Java.cast(boxRaw, ViewGroupCls);

            // Todo tick, não só na criação: loadOnlineMusicCard() repovoa a fileira
            // com os ícones do OEM sempre que a Activity é recriada.
            clearForeign(box);

            var found = boxV.findViewWithTag.overload("java.lang.Object").call(boxV, tagStr);

            var iv;
            if (found === null) {
                iv = ImageView.$new.overload("android.content.Context").call(ImageView, act);
                var ivV = Java.cast(iv, ViewCls);
                ivV.setTag.overload("java.lang.Object").call(ivV, tagStr);
                // elevation = CP virtual: é assim que o onClick do OEM identifica a view
                ivV.setElevation(CP_CARD);
                ivV.setOnClickListener(Java.cast(act, Java.use("android.view.View$OnClickListener")));
                var lp = LLParams.$new.overload("int", "int").call(LLParams, W, H);
                if (SLOT > 0) Java.cast(lp, MarginLP).setMargins(GAP, 0, 0, 0);
                ivV.setLayoutParams(Java.cast(lp, VGLayoutParams));

                var at = insertIndex(box);
                box.addView.overload("android.view.View", "int").call(box, ivV, at);
                log("card criado no container (índice " + at + ")");
                lastSig = null;                    // força a primeira pintura
            } else {
                iv = Java.cast(found, ImageView);
            }

            var sig = stateSig(s);
            if (sig !== lastSig) {
                iv.setImageBitmap(drawCard(s));
                lastSig = sig;
                log("card pintado: " + sig);
            }

            // Título e margem são da fileira inteira: só o dono escreve neles.
            if (isOwner(box)) {
                setTitleVisible(act, false);
                setRowTop(act, ROW_TOP);
            }
        } catch (e) {
            logOnce("paint:" + e, "paintCard err: " + e + (e.stack ? " | " + e.stack : ""));
        }
    }

    /** Mostra/esconde o título "Mídia online" do bloco. */
    function setTitleVisible(act, visible) {
        try {
            var raw = viewOf(act, "online_music");
            if (raw === null) return;
            var v = Java.cast(raw, ViewCls);
            var want = visible ? 0 : 8;            // VISIBLE : GONE
            if (v.getVisibility() !== want) v.setVisibility(want);
        } catch (e) {
            logOnce("title:" + e, "setTitleVisible err: " + e);
        }
    }

    /**
     * Reescreve o topMargin da fileira. Esconder o título não sobe nada sozinho:
     * a fileira é ancorada no topo do PAI com margem fixa (handoff §2). Sem isto
     * o card de 300 bateria em "Mídia local" (168 + 300 = 468 > 430).
     */
    function setRowTop(act, top) {
        try {
            var raw = viewOf(act, "horizontalScrollView");
            if (raw === null) return;
            var v   = Java.cast(raw, ViewCls);
            var lp  = v.getLayoutParams();
            if (lp === null) return;
            var mlp = Java.cast(lp, MarginLP);
            if (origRowTop === null) {
                // Se o card de clima já içou a fileira, ROW_TOP não é o valor de
                // fábrica — cair no medido evita restaurar a tela errada depois.
                var cur = mlp.topMargin.value;
                origRowTop = (cur === ROW_TOP) ? ROW_TOP_DEFAULT : cur;
                log("topMargin original da fileira = " + origRowTop
                    + (cur === ROW_TOP ? " (assumido; parceiro já tinha içado)" : ""));
            }
            var prev = mlp.topMargin.value;
            if (prev === top) return;
            mlp.topMargin.value = top;
            v.setLayoutParams(Java.cast(mlp, VGLayoutParams));
            log("topMargin da fileira: " + prev + " -> " + top);
        } catch (e) {
            logOnce("rowtop:" + e, "setRowTop err: " + e);
        }
    }

    /**
     * Remove o card e devolve título e fileira ao estado de fábrica.
     *
     * Com o card de clima ainda na tela, some SÓ com o nosso: restaurar aqui
     * chamaria loadOnlineMusicCard() (que zera o container) e devolveria título e
     * margem por cima do card dele.
     */
    function restore(act) {
        try {
            var boxRaw = viewOf(act, "online_music_container");
            var peer   = false;
            if (boxRaw !== null) {
                var boxV  = Java.cast(boxRaw, ViewCls);
                var box   = Java.cast(boxRaw, ViewGroupCls);
                var found = boxV.findViewWithTag.overload("java.lang.Object")
                    .call(boxV, Str.$new(CARD_TAG));
                if (found !== null) {
                    box.removeView.overload("android.view.View")
                        .call(box, Java.cast(found, ViewCls));
                }
                peer = peerPresent(box);
            }
            if (peer) {
                log("card removido; fileira mantida (card de clima ativo)");
                return;
            }
            setRowTop(act, origRowTop === null ? ROW_TOP_DEFAULT : origRowTop);
            setTitleVisible(act, true);
            act.loadOnlineMusicCard();       // redesenha os ícones de fábrica
            log("fileira original restaurada");
        } catch (e) {
            log("restore err: " + e);
        }
    }

    /** Acha a Activity viva e roda `fn` na main thread. Sem cache entre ticks (§7.4). */
    function withActivity(fn) {
        var act = null;
        try {
            Java.choose(ACT, {
                onMatch: function (inst) { act = inst; return "stop"; },
                onComplete: function () {}
            });
        } catch (e) {
            logOnce("choose:" + e, "choose err: " + e);
            return;
        }
        if (act === null) return;            // tela fechada: vale no próximo onCreate
        Java.scheduleOnMainThread(function () {
            try { fn(act); }
            catch (e) { logOnce("main:" + e, "main thread err (activity morta?): " + e); }
        });
    }

    // ── loop ────────────────────────────────────────────────────────────────
    function tick() {
        Java.perform(function () {
            captureOriginal();
            var s    = readCtrl();
            var want = s.on;
            var changed = (want !== lastCtrl);
            if (changed) {
                lastCtrl = want;
                enabled  = want;
                lastSig  = null;
                log("card na home = " + (want ? "ON" : "OFF"));
                applyToInstances();
            }
            if (enabled) {
                withActivity(function (act) {
                    // loadOnlineMusicCard roda no onCreate e repovoa a fileira:
                    // se a Activity foi recriada, paintCard recria o card sozinho.
                    paintCard(act, s);
                });
            } else if (changed) {
                withActivity(restore);
            }
        });
    }

    // Primeiro ciclo IMEDIATO: esperar o primeiro setInterval deixava a fileira do
    // OEM na tela por 1,5s a mais depois de a injeção já estar de pé.
    tick();
    setInterval(tick, 1500);

    // ── autoteste (§7.5): falha de helper aparece na injeção, não no carro ──
    (function selfTest() {
        var falhas = [];
        [["buildArr",   function () { return buildArr([551]).length === 1; }],
         ["wantedList", function () { return wantedList().length >= 0; }],
         ["resId",      function () { return resId("online_music") > 0; }],
         ["readCtrl",   function () { return readCtrl() !== null; }],
         ["parseCtrl",  function () {
             var p = parseCtrl("on;conn=1;auto=1;phase=80;soc=62;km=142;target=80");
             return p.on && p.conn && p.auto && p.phase === 80 && p.soc === 62 && p.km === 142;
         }],
         ["parseOff",   function () { return parseCtrl("off").on === false
                                          && parseCtrl(null).on === false; }],
         ["stateSig",   function () { return stateSig(emptyState()).length > 0; }],
         ["drawCard",   function () { return drawCard(readCtrl()) !== null; }],
         ["slotOf",     function () { return slotOf(CARD_TAG) === SLOT && slotOf("xpto") === -1; }],
         // teto rígido: 430 (topo do título "Mídia local") - ROW_TOP
         ["altura",     function () { return ROW_TOP + H <= 430; }],
         // dois cards de 536 + o respiro têm de caber nos 1158 da fileira
         ["largura",    function () { return CARD_TAGS.length * W + (CARD_TAGS.length - 1) * GAP <= 1158; }]
        ].forEach(function (par) {
            try {
                if (par[1]() !== true) falhas.push(par[0] + " (resultado inesperado)");
            } catch (e) {
                falhas.push(par[0] + ": " + e);
            }
        });
        if (falhas.length === 0) log("autoteste dos helpers: OK");
        else log("autoteste dos helpers FALHOU -> " + falhas.join(" | "));
    })();

    log("card de carga na home ativo (alvo com.beantechs.mediacenter, slot " + SLOT + ")");
});
