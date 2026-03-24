/// statusbar_view.rs — macOS 状态栏两行网速 View
///
/// 效果（等宽字体，跟随系统颜色）：
///   140K/s   ← 下行
///     6K/s   ← 上行
///
/// 注意：setView: 之后 setMenu: 不再自动弹出菜单，
///       需在 mouseDown: 里手动调用 popUpStatusItemMenu:
#[cfg(target_os = "macos")]
pub mod macos {
    use std::sync::atomic::{AtomicPtr, Ordering};
    use std::sync::OnceLock;

    use cocoa::appkit::{NSVariableStatusItemLength};
    use cocoa::base::{id, nil, YES};
    use cocoa::foundation::{NSPoint, NSRect, NSSize, NSString};
    use objc::declare::ClassDecl;
    use objc::runtime::{Class, Object, Sel};
    use objc::{class, msg_send, sel, sel_impl};

    // ── 全局：持有 view / statusItem / menu 指针 ─────────────────────────────
    static VIEW_PTR:        AtomicPtr<Object> = AtomicPtr::new(std::ptr::null_mut());
    static STATUS_ITEM_PTR: AtomicPtr<Object> = AtomicPtr::new(std::ptr::null_mut());
    static MENU_PTR:        AtomicPtr<Object> = AtomicPtr::new(std::ptr::null_mut());

    static CURRENT_RX: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());
    static CURRENT_TX: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

    // ── 注册自定义 NSView 子类 ────────────────────────────────────────────────
    fn register_class() -> &'static Class {
        static CLASS: OnceLock<&'static Class> = OnceLock::new();
        CLASS.get_or_init(|| {
            let superclass = class!(NSView);
            let mut decl = ClassDecl::new("SysMonSpeedView", superclass)
                .expect("SysMonSpeedView already registered or failed");
            unsafe {
                decl.add_method(
                    sel!(drawRect:),
                    draw_rect as extern "C" fn(&Object, Sel, NSRect),
                );
                // 点击时弹出菜单
                decl.add_method(
                    sel!(mouseDown:),
                    mouse_down as extern "C" fn(&Object, Sel, id),
                );
            }
            decl.register()
        })
    }

    /// mouseDown: — 手动弹出菜单（setView: 后系统不再自动处理）
    extern "C" fn mouse_down(_this: &Object, _sel: Sel, _event: id) {
        unsafe {
            let item = STATUS_ITEM_PTR.load(Ordering::SeqCst) as id;
            let menu = MENU_PTR.load(Ordering::SeqCst) as id;
            if !item.is_null() && !menu.is_null() {
                let _: () = msg_send![item, popUpStatusItemMenu: menu];
            }
        }
    }

    /// drawRect: — 绘制两行文字
    extern "C" fn draw_rect(this: &Object, _sel: Sel, _rect: NSRect) {
        unsafe {
            let rx = CURRENT_RX.lock().unwrap().clone();
            let tx = CURRENT_TX.lock().unwrap().clone();

            let bounds: NSRect = msg_send![this, bounds];
            let h = bounds.size.height; // 通常 22pt

            // 等宽字体 9pt
            let font: id = msg_send![
                class!(NSFont),
                monospacedSystemFontOfSize: 9.0_f64
                weight: 0.0_f64
            ];

            // 使用系统 labelColor，自动跟随深色/浅色模式（状态栏通常显示为白色）
            let color: id = msg_send![class!(NSColor), labelColor];

            // 段落样式：右对齐
            let para: id = msg_send![class!(NSMutableParagraphStyle), new];
            let _: () = msg_send![para, setAlignment: 2u64]; // NSTextAlignmentRight

            let line_h = h / 2.0;

            // 上半行：下行速度（↓）
            draw_attributed_string(&rx, font, color, para, 0.0, line_h, bounds.size.width);
            // 下半行：上行速度（↑）
            draw_attributed_string(&tx, font, color, para, 0.0, 0.0, bounds.size.width);
        }
    }

    unsafe fn draw_attributed_string(
        text: &str,
        font: id,
        color: id,
        para_style: id,
        x: f64,
        y: f64,
        width: f64,
    ) {
        let ns_str: id = NSString::alloc(nil).init_str(text);

        // 用 NSMutableDictionary 逐个添加属性
        let attrs: id = msg_send![class!(NSMutableDictionary), dictionaryWithCapacity: 3usize];

        let font_key: id = NSString::alloc(nil).init_str("NSFont");
        let _: () = msg_send![attrs, setObject: font forKey: font_key];

        let color_key: id = NSString::alloc(nil).init_str("NSColor");
        let _: () = msg_send![attrs, setObject: color forKey: color_key];

        let para_key: id = NSString::alloc(nil).init_str("NSParagraphStyle");
        let _: () = msg_send![attrs, setObject: para_style forKey: para_key];

        // 创建 NSAttributedString
        let attr_str: id = msg_send![class!(NSAttributedString), alloc];
        let attr_str: id = msg_send![attr_str, initWithString: ns_str attributes: attrs];

        let rect = NSRect {
            origin: NSPoint { x, y },
            size: NSSize { width, height: 12.0 },
        };
        let _: () = msg_send![attr_str, drawInRect: rect];
    }

    // ── 公开接口 ──────────────────────────────────────────────────────────────

    /// 创建状态栏 item，绑定自定义两行 view（主线程调用）
    /// `menu`：已构建好的 NSMenu id
    pub unsafe fn create_status_item(menu: id) -> id {
        let status_bar: id = msg_send![class!(NSStatusBar), systemStatusBar];
        let item: id = msg_send![status_bar, statusItemWithLength: NSVariableStatusItemLength];
        let _: () = msg_send![item, retain];

        // 保存 statusItem 和 menu 指针，供 mouseDown: 回调使用
        STATUS_ITEM_PTR.store(item as *mut Object, Ordering::SeqCst);
        MENU_PTR.store(menu as *mut Object, Ordering::SeqCst);

        // 注意：setView: 之后 setMenu: 不再生效，改为在 mouseDown: 里手动弹出
        // 这里不调用 setMenu:，避免干扰

        // 创建自定义 view（宽 72pt，高 22pt）
        let cls = register_class();
        let view: id = msg_send![cls, alloc];
        let frame = NSRect {
            origin: NSPoint { x: 0.0, y: 0.0 },
            size: NSSize { width: 72.0, height: 22.0 },
        };
        let view: id = msg_send![view, initWithFrame: frame];
        let _: () = msg_send![view, retain];

        let _: () = msg_send![item, setView: view];

        VIEW_PTR.store(view as *mut Object, Ordering::SeqCst);

        // 初始文字
        *CURRENT_RX.lock().unwrap() = format_speed_line("↓", 0.0);
        *CURRENT_TX.lock().unwrap() = format_speed_line("↑", 0.0);

        item
    }

    /// 更新网速并触发重绘
    pub fn update_speed(rx_kbps: f64, tx_kbps: f64) {
        *CURRENT_RX.lock().unwrap() = format_speed_line("↓", rx_kbps);
        *CURRENT_TX.lock().unwrap() = format_speed_line("↑", tx_kbps);
        redraw();
    }

    /// 显示停止状态
    pub fn show_stopped() {
        *CURRENT_RX.lock().unwrap() = "↓  --".to_string();
        *CURRENT_TX.lock().unwrap() = "↑  --".to_string();
        redraw();
    }

    fn redraw() {
        let view = VIEW_PTR.load(Ordering::SeqCst);
        if !view.is_null() {
            unsafe {
                let _: () = msg_send![view as id, setNeedsDisplay: YES];
            }
        }
    }

    /// 格式化单行网速，右对齐固定 7 字符
    /// 示例："↓ 140K/s"  "↑   6K/s"  "↓ 1.2M/s"
    fn format_speed_line(arrow: &str, kbps: f64) -> String {
        if kbps >= 1024.0 {
            format!("{} {:>5.1}M/s", arrow, kbps / 1024.0)
        } else {
            format!("{} {:>5.0}K/s", arrow, kbps)
        }
    }
}
