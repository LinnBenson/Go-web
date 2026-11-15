# 第三方组件

- Mozilla GeckoView 160.0.20261104181552，Mozilla Public License 2.0。官方对应源代码：https://hg.mozilla.org/releases/mozilla-release/rev/d84e7a6936554efe26097d9d9b24a0e11330e630 。内核未经修改，应用只使用公开嵌入接口与内置扩展。
- Gecko 所包含第三方组件的完整许可清单：https://www.mozilla.org/en-US/about/legal/terms/firefox/ ，以及内核内置 `about:license` 页面。
- AndroidX：Apache License 2.0，https://android.googlesource.com/platform/frameworks/support/ 。
- Kotlin 标准库：Apache License 2.0，https://github.com/JetBrains/kotlin 。
- SnakeYAML：Apache License 2.0，https://bitbucket.org/snakeyaml/snakeyaml 。
- Google Play Services FIDO：依 GeckoView 官方依赖引入，条款见 https://developers.google.com/terms 。

完整依赖版本以 Gradle 解析结果及 Mozilla 发布的 POM 为准。使用 APK 的内置内核不需要安装 Firefox。
