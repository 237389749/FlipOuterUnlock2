#!/system/bin/sh
# FlipUnlock 属性层安装检查 (2026-08-16, 方案A 安装时判断)
#
# ⚠️ 目的: 防止"单独装 KSU 属性模块(属性1=伪装手机)而无 LSP 模块"——
#   该组合会导致 SystemUI TinyKeyguardPanel NPE 崩溃环(refMD FoldState §38:
#   isFlipDevice→false 禁止作用于 SystemUI, 需 LSP DeviceIdentityHook 身份排除)。
#   安装时(系统已运行, pm 可用)检测 com.example.flipunlock 是否安装,
#   未装 → abort 阻止安装; 已装 → 继续。
#   (运行时仍有 service.sh 方案A(文件系统glob)+方案B(崩溃自愈)兜底。)

if ! pm path com.example.flipunlock >/dev/null 2>&1; then
    ui_print ""
    ui_print "  [FlipUnlock] ⚠️ LSP 模块 com.example.flipunlock 未安装!"
    ui_print "  单独安装本模块(属性1)会导致 SystemUI 崩溃环(§38)。"
    ui_print "  请先安装/启用 LSP 模块(com.example.flipunlock), 再安装本模块。"
    ui_print "  (若需强制安装: 手动把模块目录放进 /data/adb/modules/ 并重启)"
    ui_print ""
    abort "Aborting: LSP module com.example.flipunlock not installed"
fi
ui_print "  [FlipUnlock] ✓ LSP 模块已安装, 继续安装属性层"
