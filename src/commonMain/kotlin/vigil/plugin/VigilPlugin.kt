package vigil.plugin

// NestJS の Module / Provider ライフサイクルフックに相当。
// プラグインは VigilEngine に登録し、ライフサイクルに沿って呼び出される。
interface VigilPlugin {
    val name: String
    suspend fun onInitialize(context: PluginContext)
    suspend fun onHeartbeat(context: PluginContext)
    suspend fun onShutdown()
}
