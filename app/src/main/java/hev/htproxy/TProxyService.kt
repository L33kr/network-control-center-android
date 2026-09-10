package hev.htproxy

object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray
}
