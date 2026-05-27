# Haval EV Manager

## Versioning rule
Before every commit+push, increment the version in `app/build.gradle.kts`:
- `versionCode` → +1 (integer, always increments by 1)
- `versionName` → semver: patch for fixes, minor for new features, major for breaking changes

## Display do Multimedia Haval (medido em campo — 2026-05-10)

| Campo | Valor |
|---|---|
| Resolução usável (px) | 1792 × 720 |
| Resolução física real (px) | 1920 × 720 |
| Área ocupada pela status bar | 128 px na horizontal |
| Tamanho usável (dp) | 1792 × 720 dp |
| screenWidthDp | 1792 dp |
| screenHeightDp | 660 dp ← área útil abaixo da status bar |
| smallestScreenWidthDp | 720 dp |
| Densidade lógica (dpi) | 160 dpi (mdpi) |
| Fator de escala | **1.00** → 1 dp = 1 px exato |
| DPI físico X/Y | 320.0 dpi |
| Proporção W/H | ~2.49 (aprox. 12:5) |

**Regras para layouts nesta tela:**
- Use `dp` normalmente — como o fator é 1.00, dp == px neste device.
- Área de trabalho real do app: **1792 × 660 dp** (descontando a status bar de ~60dp no topo).
- A tela é muito mais larga que alta (2.49:1) — prefira layouts horizontais, evite `Column` longas que precisam de scroll.
- `fillMaxSize()` ocupa os 1792 × 660 dp úteis.

## Propriedades EV monitoradas

| Chave | Comportamento |
|-------|--------------|
| `car.ev_setting.power_model_config` | Clicável — cicla 0→1→2→0 (bloqueado quando Auto ativo) |
| `car.ev_setting.charge_soc_target_config` | Somente leitura (gerenciado pelo Auto quando ativo) |
| `car.ev_setting.power_reserve_config` | Clicável — cicla 0→1→2→0 (bloqueado quando Auto ativo) |
| `car.ev_info.cur_battery_power_percentage` | Somente leitura — % da bateria (usado pelo ciclo automático) |
| `car.ev_info.electric_mode_remain_odometer` | Somente leitura — km restantes no modo elétrico (informativo no BatteryCard) |
| `car.ev_info.fuel_mode_remain_odometer` | Somente leitura — autonomia no modo combustão restante em km (card próprio) |
| `car.basic.engine_state` | Somente leitura — 13=ligado (combustão), 11(ou outro)=desligado; persiste timestamps de mudança |
| `car.ev_setting.wade_mode_enable` | Somente escrita — pulso 1→0 disparado pelo Auto HEV |

## Ciclo automático (AutoToggleCard)

Quando ativado:
- Define `power_model_config = 0` (HEV) e `power_reserve_config = 0` (reserva prioritária)
- Cicla `charge_soc_target_config` entre 80 e 20 baseado em `cur_battery_power_percentage`:
  - Fase carregando (`lastSocTarget = 80`): aguarda bateria ≥ 75% → seta SOC = 20
  - Fase descarregando (`lastSocTarget = 20`): aguarda bateria ≤ 20% → seta SOC = 80
- Persiste `auto_enabled`, `last_soc_target` e `saved_power_model_config` em SharedPreferences (sobrevive reinício)
- Ao ativar: salva o valor atual de `power_model_config` em `saved_power_model_config`
- Ao desativar: restaura `power_model_config` ao valor salvo e remove a chave
- Chave especial `"auto_enabled"` no `commandCallback` do serviço — não enviada ao carro

## Auto HEV (AutoHevCard)

Quando ativado:
- Loop de 1 minuto verifica condições: `last_engine_change_ms` > 24h E `remain_odometer` > 100 E `cur_battery_power_percentage` < 80
- Se condições atendidas: envia `wade_mode_enable = 1`, aguarda 2s, envia `wade_mode_enable = 0`
- Após disparo, atualiza `last_engine_change_ms` para agora (reset do timer de 24h)
- Persiste `auto_hev_enabled`, `last_engine_state_1_ms`, `last_engine_change_ms` em SharedPreferences
- Registra mudanças de `engine_state` (13↔outro) com timestamp — nunca sobrescreve com primeira leitura
- `engine_state = 13` → motor a combustão ligado; qualquer outro valor (ex: 11) → desligado
