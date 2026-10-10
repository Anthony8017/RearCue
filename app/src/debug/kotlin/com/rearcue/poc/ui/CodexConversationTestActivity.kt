package com.rearcue.poc.ui

import android.os.Bundle
import androidx.activity.ComponentActivity

/** 真机 Compose 测试宿主：只显示测试内容，保持系统解锁门槛不变。 */
class CodexConversationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }
}
