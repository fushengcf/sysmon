# dmg_settings.py — dmgbuild 配置文件
# 用法：dmgbuild -s assets/dmg_settings.py "SysMon WS" dist/SysMon-WS-0.1.0.dmg

import os

# DMG 卷标
appname = "SysMon WS"

# 要打包的文件（使用绝对路径，dmgbuild exec 模式下 __file__ 不可用）
_base = os.environ.get("SYSMON_PROJECT_DIR", os.getcwd())
application = os.path.join(_base, "dist", "SysMon WS.app")

# dmgbuild 配置
files = [application]
symlinks = {"Applications": "/Applications"}

# 窗口外观
background = None
icon_size = 128
window_rect = ((200, 200), (540, 380))
icon_locations = {
    "SysMon WS.app": (160, 180),
    "Applications":  (380, 180),
}

# 格式
format = "UDZO"
compression_level = 9
