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

## Ciclo automático (AutoToggleCard)

Quando ativado:
- Define `power_model_config = 0` (HEV) e `power_reserve_config = 0` (reserva prioritária)
- Cicla `charge_soc_target_config` entre 80 e 20 baseado em `cur_battery_power_percentage`:
  - Fase carregando (`lastSocTarget = 80`): aguarda bateria ≥ 75% → seta SOC = 20
  - Fase descarregando (`lastSocTarget = 20`): aguarda bateria ≤ 20% → seta SOC = 80
- Persiste `auto_enabled` e `last_soc_target` em SharedPreferences (sobrevive reinício)
- Chave especial `"auto_enabled"` no `commandCallback` do serviço — não enviada ao carro
