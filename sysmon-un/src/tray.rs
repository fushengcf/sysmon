/// tray.rs �?Linux 系统托盘模块（StatusNotifierItem / SNI�?
///
/// 使用 ksni 库通过 D-Bus 注册 StatusNotifierItem
/// 兼容 Ubuntu 25 桌面（GNOME / KDE / X11 / Wayland�?
use std::sync::{mpsc, Arc};
use std::sync::atomic::{AtomicBool, Ordering};

use ksni::{menu::StandardItem, Icon, MenuItem, Tray};

/// 托盘动作
pub enum TrayAction {
    Toggle,
    Quit,
}

/// 系统托盘实现
pub struct SysmonTray {
    pub rx: mpsc::Receiver<(f64, f64, Option<f32>, Option<f32>)>,
    pub action_tx: mpsc::Sender<TrayAction>,
    pub ws_running: Arc<AtomicBool>,
}

impl Tray for SysmonTray {
    fn id(&self) -> String {
        "sysmon-un".into()
    }

    fn title(&self) -> String {
        "sysmon-un".into()
    }

    fn icon_pixmap(&self) -> Vec<Icon> {
        vec![generate_icon()]
    }

    fn status(&self) -> ksni::Status {
        ksni::Status::Active
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        // 消费最新指标用�?tooltip
        let mut tip = "sysmon-un: loading...".to_string();
        while let Ok((rx, tx, cpu, mem)) = self.rx.try_recv() {
            let cpu_str = cpu.map(|c| format!("{:.0}%", c)).unwrap_or_else(|| "--".into());
            let mem_str = mem.map(|m| format!("{:.0}%", m)).unwrap_or_else(|| "--".into());
            tip = format!(
                "sysmon-un\n↓{:.0} ↑{:.0} KB/s\nCPU {} MEM {}",
                rx, tx, cpu_str, mem_str
            );
        }
        ksni::ToolTip {
            title: "sysmon-un".into(),
            description: tip,
            icon_name: String::new(),
            icon_pixmap: vec![generate_icon()],
        }
    }

    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        let running = self.ws_running.load(Ordering::SeqCst);
        vec![
            StandardItem {
                label: "sysmon-un".into(),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuItem::Separator.into(),
            StandardItem {
                label: if running {
                    "�?停止服务".into()
                } else {
                    "�?启动服务".into()
                },
                activate: Box::new(|this: &mut Self| {
                    let _ = this.action_tx.send(TrayAction::Toggle);
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator.into(),
            StandardItem {
                label: "退�?.into(),
                activate: Box::new(|this: &mut Self| {
                    let _ = this.action_tx.send(TrayAction::Quit);
                }),
                ..Default::default()
            }
            .into(),
        ]
    }
}

/// 生成 22×22 绿色圆形图标
fn generate_icon() -> Icon {
    let size: i32 = 22;
    let mut data = vec![0u8; (size * size * 4) as usize];
    let cx = size as f32 / 2.0;
    let cy = size as f32 / 2.0;
    let r = size as f32 / 2.0 - 1.5;

    for y in 0..size as i32 {
        for x in 0..size as i32 {
            let idx = ((y * size + x) * 4) as usize;
            let dist = ((x as f32 - cx).powi(2) + (y as f32 - cy).powi(2)).sqrt();
            if dist < r {
                // 渐变效果：边缘稍�?
                let intensity = 1.0 - (dist / r) * 0.3;
                data[idx] = (0x3F as f32 * intensity) as u8;
                data[idx + 1] = (0xB9 as f32 * intensity) as u8;
                data[idx + 2] = (0x50 as f32 * intensity) as u8;
                data[idx + 3] = 0xFF;
            }
        }
    }

    Icon {
        width: size,
        height: size,
        data,
    }
}
