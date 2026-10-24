package br.com.redesurftank.havalevmanager.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

import br.com.redesurftank.havalevmanager.App;
import rikka.shizuku.Shizuku;

/**
 * Publica o estado do ciclo automático para o card na Home da MediaCenter.
 *
 * Este app NÃO injeta nada. A tentativa de v1.7.0 (agente Frida próprio dentro de
 * com.beantechs.mediacenter, convivendo com o do haval-climate-control) derrubou o
 * ART em campo — SIGSEGV em MterpInvokeVirtual, dois agentes hookando os mesmos
 * ArtMethod e dois watchdogs fazendo pkill + reinjetar a cada 10s (ver o revert
 * 88b1425). O desenho agora é de agente único: quem injeta e pinta é o script
 * com_beantechs_mediacenter_card.js do haval-climate-control, e a única coisa que
 * sai daqui é esta linha de texto.
 *
 * Formato (uma linha, lida pelo script a cada 1,5 s):
 *
 *   conn=1;auto=1;phase=80;soc=62;model=0;ts=1761300000
 *
 *   conn   1 quando o serviço está ligado ao veículo
 *   auto   1 quando o ciclo automático está ativo
 *   phase  last_soc_target — 80 = fase CARGA, 20 = fase USO
 *   soc    car.ev_info.cur_battery_power_percentage, ou "--"
 *   model  car.ev_setting.power_model_config (0 = HEV, 1 = EV), ou "--"
 *   ts     epoch em segundos; o script ignora a linha com mais de 90 s
 *          (serviço morto/parado não deve deixar número velho na tela)
 */
public final class HomeCardState {

    private static final String TAG  = "HomeCardState";
    public  static final String PATH = "/data/local/tmp/haval_ev_home_card";

    /** Nome do arquivo temporário no cacheDir — o `cp` via Shizuku lê daqui. */
    private static final String TMP_NAME = "haval_ev_home_card";

    private HomeCardState() {}

    /**
     * Grava a linha. Chamada do backgroundHandler do serviço (pushState e o
     * heartbeat de 15 s): nunca da main thread, porque o Shizuku faz IPC síncrono.
     */
    public static void publish(String line) {
        if (line == null || !Shizuku.pingBinder()) return;
        try {
            File tmp = new File(App.getContext().getCacheDir(), TMP_NAME);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(line.getBytes("UTF-8"));
            }
            ShizukuUtils.runCommandAndGetOutput(new String[]{"sh", "-c",
                    "cp -f " + tmp.getAbsolutePath() + " " + PATH + " && chmod 644 " + PATH});
        } catch (Exception e) {
            Log.w(TAG, "falha ao gravar " + PATH + ": " + e.getMessage());
        }
    }

    /**
     * Apaga o arquivo. Chamado no onDestroy: sem isto o card ficaria na Home com o
     * último valor até o `ts` vencer.
     */
    public static void clear() {
        if (!Shizuku.pingBinder()) return;
        try {
            ShizukuUtils.runCommandAndGetOutput(new String[]{"rm", "-f", PATH});
        } catch (Exception e) {
            Log.w(TAG, "falha ao apagar " + PATH + ": " + e.getMessage());
        }
    }
}
