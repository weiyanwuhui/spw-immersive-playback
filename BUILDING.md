# 构建沉浸播放

需要 PowerShell 7、JDK 25、Kotlin／Compose compiler 2.4.20、Kotlin stdlib／Metadata JVM 2.4.20、annotations 和本地安装的 SPW。工具当前在 `../../开发资料/tools/`，可用 -ToolsRoot 指定其他目录。

从本 Mod 根目录执行：

```powershell
New-Item -ItemType Directory -Path build -Force | Out-Null
& ./build.ps1 -HostJar 'E:/Steam/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll' -ToolsRoot '../开发资料/tools' *> build/build.log
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& ./scripts/AuditRelease.ps1 -Version 0.1.31 -Package build/libs/immersive-playback-0.1.31.spmod -BuildLog build/build.log -SourceRoot src/main
```

从工作区根也可调用 `& './沉浸播放/build.ps1'`，测试期间使用本工程目录，结束后恢复。JDK 可用 -JdkHome 指定。

shared/、资源、测试和三项配置均在本工程内，不依赖歌词增强。输出位于 build/libs/，release/ 仅放已经审计的交付副本。

编译保留原始 stdlib 和旧参考宿主，运行测试使用实际宿主。主程序、JDK、编译器与构建缓存不进入插件包，不将 SPW 主程序发布到 GitHub。
