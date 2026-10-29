// 根项目仅声明插件版本，不引入应用依赖
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // kotlinx.serialization 插件：Profile 等数据类使用 @Serializable
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
