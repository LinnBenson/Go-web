# 本地密码本

入口位于菜单第二行第三列，使用独立且不导出的 VaultActivity。首次进入两次确认 8 位数字 PIN；每次进入需要解锁，Activity 离开前台即清空界面和内存记录、释放派生密钥并结束。解锁界面使用 11 键圆环（数字 0–9、退格），不调用网页或浏览器存储。

数据存于 noBackupFilesDir/password-book.v1，通过 AtomicFile 提交。PIN 使用随机 16 字节盐、PBKDF2-HMAC-SHA256（210000 次）派生 256 位密钥，以 AES-GCM 加密记录；盐与内层密文再由当前安装的 Android Keystore AES-256-GCM 密钥封装。每次加密使用新 IV，认证附加数据标识格式版本。没有明文 PIN 或独立 PIN 校验摘要。关闭后清理可控的 PIN/密钥字节数组；Java 字符串与引擎内部副本不保证可立即擦除。

密码本窗口启用 FLAG_SECURE，密码本字段不参与系统自动填充，不主动写入剪贴板。连续错误输入从第 5 次开始延迟重试，重试状态保存在本机。密码本保存失败时保留旧文件，成功后才更新列表。上限为 200 条记录，单条字段与文件大小有界。

网页填充由用户点原生图标发起，每次先验证 PIN，再选择一条账号。请求绑定 tab、frame、文档令牌、URL、表单和原输入框；后台与内容脚本分别校验，跳转、超时、隐藏或替换输入框后拒绝填充。凭据通过进程内一次性交接对象传递，30 秒过期清理，不放入 Intent、剪贴板或浏览器持久存储。消息只发送给目标 frame，不广播账号数据。

账号字段用于填写同表单中识别到的用户名、邮箱或手机号，没有对应字段时只填密码。通过原生 value setter 写入并派发 input/change，不自动提交。普通 iframe 和开放 Shadow DOM 中的标准 input 可检测，自绘控件及关闭的 Shadow DOM 不保证支持。

网页缓存清理不涉及密码本文件或 Keystore。没有同步、导出、找回 PIN 或设备迁移功能；卸载、清除应用数据、Keystore 丢失或遗忘 PIN 均可能使记录不可恢复。

参考：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)。
