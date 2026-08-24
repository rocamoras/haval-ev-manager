package br.com.redesurftank.havalevmanager.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import br.com.redesurftank.havalevmanager.App;
import br.com.redesurftank.havalevmanager.R;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Injeção do card de carga na Home da MediaCenter, via Shizuku.
 *
 * Portado do app irmão haval-climate-control, que injeta o card de clima no MESMO
 * processo. Os dois convivem porque:
 *   - cada um tem o seu script (o {@code pkill -f} usa o nome do script, e
 *     "com_beantechs_mediacenter_card" não casa com "..._ev_card" nem o contrário);
 *   - o fridaserver é único e compartilhado — só cai quando NENHUM script dos dois
 *     apps está injetado (ver {@link #EXTERNAL_SCRIPTS});
 *   - o binário não é reextraído com o server de pé (ETXTBSY, e trocaria a versão
 *     debaixo da injeção viva);
 *   - a disputa pelo container é resolvida dentro do script (protocolo de slots).
 *
 * Requer os binários arm64 {@code fridaserver} e {@code fridainject} em res/raw. No
 * repositório eles são placeholders minúsculos; o build do CI substitui pelos reais
 * (16.7.19 — a mesma versão usada pelo climate-control).
 */
public class FridaUtils {
    private static final String TAG = "FridaUtils";

    public static final String FRIDA_SERVER_PATH   = "/data/local/tmp/fridaserver";
    public static final String FRIDA_INJECTOR_PATH = "/data/local/tmp/fridainjector";

    /** Arquivo de estado lido em runtime pelo script (ver cabeçalho do .js). */
    public static final String HOME_CARD_CTRL_PATH = "/data/local/tmp/haval_ev_home_card";

    private static final String MEDIA_CENTER_PROC = "com.beantechs.mediacenter";
    private static final String SCRIPT_NAME       = "com_beantechs_mediacenter_ev_card";
    private static final String SCRIPT_PATH       = "/data/local/tmp/" + SCRIPT_NAME + ".js";
    private static final String SCRIPT_LOG        = "/data/local/tmp/" + SCRIPT_NAME + ".log";

    /**
     * Scripts de OUTROS apps que compartilham este fridaserver — os dois alvos do
     * haval-climate-control. Derrubar o server com um deles vivo mataria a injeção
     * do vizinho junto.
     */
    private static final String[] EXTERNAL_SCRIPTS = {
            "com_beantechs_mediacenter_card", "com_android_systemui"
    };

    /** Teto de espera pelo fridaserver recém-iniciado. */
    private static final long SERVER_READY_TIMEOUT_MS = 2_500;
    private static final long SERVER_POLL_MS          = 100;

    /** Abaixo disso o arquivo em res/raw é um placeholder, não o binário real. */
    private static final long MIN_REAL_BINARY_BYTES = 100_000L;

    /** Último conteúdo gravado no arquivo de estado — evita gravação redundante. */
    private static volatile String lastCtrlContent = null;

    /** true se os binários reais do Frida estão embutidos neste APK. */
    public static boolean fridaToolsEmbedded() {
        try {
            return rawResourceSize(R.raw.fridaserver) > MIN_REAL_BINARY_BYTES
                    && rawResourceSize(R.raw.fridainject) > MIN_REAL_BINARY_BYTES;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Card na Home
    // ─────────────────────────────────────────────────────────────

    /**
     * Liga o card e injeta. O script relê o arquivo de estado a cada 1,5 s, então
     * chamar com a injeção já de pé só atualiza os valores.
     */
    public static String startHomeCard(String stateLine) {
        writeCtrl(stateLine, true);
        return inject();
    }

    /**
     * Desliga o card. Só grava "off" e deixa a injeção viva: matar o injetor na
     * hora congelaria a tela com a fileira vazia até a MediaCenter reiniciar. Quem
     * encerra de fato é {@link #stopHomeCardInjection()}, chamado pelo serviço
     * depois que o script teve tempo de restaurar.
     */
    public static String stopHomeCard() {
        writeCtrl("off", true);
        return "Card na home desligado";
    }

    /** Atualiza os valores exibidos. Não faz nada se a linha não mudou. */
    public static void pushHomeCardState(String stateLine) {
        writeCtrl(stateLine, false);
    }

    /** Encerra o injetor deste app. Chamar só depois do restore do script. */
    public static String stopHomeCardInjection() {
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", SCRIPT_NAME});
            if (!anyInjectionAlive()) {
                ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", "fridaserver"});
                return "Frida encerrado (nenhuma outra injeção ativa)";
            }
            return "Injeção encerrada (fridaserver mantido para o app vizinho)";
        } catch (Exception e) {
            return "Erro ao encerrar: " + e.getMessage();
        }
    }

    public static boolean isHomeCardInjectionAlive() {
        return Shizuku.pingBinder() && scriptAlive(SCRIPT_NAME);
    }

    /** pid atual da MediaCenter (vazio se não estiver rodando). */
    public static String mediaCenterPid() {
        String pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                "ps -A | grep ' " + MEDIA_CENTER_PROC + "' | awk '{print $2}'"}).trim();
        if (pid.contains("\n")) pid = pid.split("\n")[0].trim();
        if (pid.isEmpty())
            pid = ShizukuUtils.runCommandAndGetOutput(new String[]{"pidof", MEDIA_CENTER_PROC}).trim();
        if (pid.contains(" ")) pid = pid.split(" ")[0].trim();
        return pid;
    }

    // ─────────────────────────────────────────────────────────────
    // Mecânica
    // ─────────────────────────────────────────────────────────────

    /** Extrai binários+script, garante o fridaserver de pé e injeta na MediaCenter. */
    public static String inject() {
        if (!Shizuku.pingBinder()) return "Shizuku indisponível";
        if (!fridaToolsEmbedded())
            return "Binários do Frida ausentes neste APK (placeholder). Use o APK do Release/CI.";
        try {
            // Com o server já de pé (nosso ou do app vizinho), sobrescrever o binário
            // dá ETXTBSY e ainda arriscaria trocar a versão debaixo da injeção viva.
            boolean serverRunning = !ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pidof", "fridaserver"}).trim().isEmpty();
            if (!serverRunning) {
                if (!extract(R.raw.fridaserver, FRIDA_SERVER_PATH)) return "Falha ao extrair fridaserver";
                if (!extract(R.raw.fridainject, FRIDA_INJECTOR_PATH)) return "Falha ao extrair fridainjector";
            }
            if (!extract(R.raw.com_beantechs_mediacenter_ev_card, SCRIPT_PATH))
                return "Falha ao extrair script";

            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            // Num único shell: cada newProcess() custa um fork+binder, e no boot esses
            // milissegundos são exatamente a janela em que a fileira do OEM aparece.
            svc.newProcess(new String[]{"/bin/sh", "-c",
                    "setenforce 0; chmod 755 " + FRIDA_SERVER_PATH + " " + FRIDA_INJECTOR_PATH},
                    null, null).waitFor();

            if (!serverRunning) {
                svc.newProcess(new String[]{"/bin/sh", "-c",
                        "setsid " + FRIDA_SERVER_PATH + " >/dev/null 2>&1 < /dev/null &"},
                        null, null).waitFor();
                if (!waitForFridaServer(SERVER_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "[frida] fridaserver não respondeu em "
                            + SERVER_READY_TIMEOUT_MS + "ms, injetando de todo jeito");
                }
            }

            String pid = mediaCenterPid();
            if (pid.isEmpty()) return MEDIA_CENTER_PROC + " não encontrado (pid vazio)";

            // Idempotente: remove injeções anteriores DESTE script antes de subir uma nova.
            ShizukuUtils.runCommandAndGetOutput(new String[]{"pkill", "-f", SCRIPT_NAME});

            String cmd = "setsid " + FRIDA_INJECTOR_PATH + " -D local -p " + pid
                    + " -s " + SCRIPT_PATH + " > " + SCRIPT_LOG + " 2>&1 < /dev/null &";
            svc.newProcess(new String[]{"/bin/sh", "-c", cmd}, null, null).waitFor();
            Log.w(TAG, "[frida] injetado em " + MEDIA_CENTER_PROC + " pid=" + pid);
            return "Injetado em " + MEDIA_CENTER_PROC + " (pid " + pid + ")";
        } catch (Exception e) {
            Log.e(TAG, "[frida] erro: " + e.getMessage(), e);
            return "Erro: " + e.getMessage();
        }
    }

    /**
     * Grava a linha de estado lida pelo script. Com {@code force=false} só escreve
     * se o conteúdo mudou — pushState() do serviço chama a cada dado do veículo.
     */
    private static void writeCtrl(String line, boolean force) {
        if (line == null) return;
        if (!force && line.equals(lastCtrlContent)) return;
        if (!Shizuku.pingBinder()) return;
        try {
            File tmp = new File(App.getContext().getCacheDir(), "haval_ev_home_card");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(line.getBytes("UTF-8"));
            }
            ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"cp", "-f", tmp.getAbsolutePath(), HOME_CARD_CTRL_PATH});
            ShizukuUtils.runCommandAndGetOutput(new String[]{"chmod", "644", HOME_CARD_CTRL_PATH});
            lastCtrlContent = line;
        } catch (Exception e) {
            Log.e(TAG, "[frida] falha ao gravar " + HOME_CARD_CTRL_PATH + ": " + e.getMessage(), e);
        }
    }

    private static boolean anyInjectionAlive() {
        if (scriptAlive(SCRIPT_NAME)) return true;
        for (String s : EXTERNAL_SCRIPTS) if (scriptAlive(s)) return true;
        return false;
    }

    /** true se existe um injetor rodando com este nome de script na linha de comando. */
    private static boolean scriptAlive(String scriptName) {
        try {
            return !ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pgrep", "-f", scriptName}).trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** true assim que {@code pidof fridaserver} responde, ou false no timeout. */
    private static boolean waitForFridaServer(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!ShizukuUtils.runCommandAndGetOutput(
                    new String[]{"pidof", "fridaserver"}).trim().isEmpty()) return true;
            Thread.sleep(SERVER_POLL_MS);
        }
        return false;
    }

    private static long rawResourceSize(int resId) throws Exception {
        try (InputStream in = App.getContext().getResources().openRawResource(resId)) {
            long total = 0;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) total += r;
            return total;
        }
    }

    private static boolean extract(int resId, String destPath) {
        try {
            File tmp = new File(App.getContext().getCacheDir(), new File(destPath).getName());
            try (InputStream in = App.getContext().getResources().openRawResource(resId);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"cp", "-f", tmp.getAbsolutePath(), destPath});
            return true;
        } catch (Exception e) {
            Log.e(TAG, "[frida] extract falhou (" + destPath + "): " + e.getMessage(), e);
            return false;
        }
    }
}
