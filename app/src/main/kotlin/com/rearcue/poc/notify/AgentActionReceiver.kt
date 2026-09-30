package com.rearcue.poc.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.RearCueApp
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agent.SessionActionRequest

/**
 * Agent 提醒通知的动作按钮落点（spec 0018-4 / 票 #174）：「同意/拒绝」与选项点选都走这一个
 * 入口——把动作交给 [com.rearcue.poc.AppContainer.sendAgentAction] 同一条会话动作链（桥契约
 * POST /action）。**没有自由文字入口**（ADR 0009 红线）：本 Receiver 只认三类动作词。
 */
class AgentActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val kind = SessionActionKind.fromWire(intent.getStringExtra(EXTRA_ACTION))
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID)
        if (sessionId.isEmpty() || kind == null) {
            Log.w(LOG_TAG, "agent action 广播缺参，忽略 sessionId=$sessionId action=${intent.getStringExtra(EXTRA_ACTION)}")
            return
        }
        val container = (context.applicationContext as? RearCueApp)?.container ?: return
        // 动作请求在入口组装（review 2026-09-30）：全链传 SessionActionRequest，不裸传三元组。
        container.sendAgentAction(SessionActionRequest.of(sessionId, kind, optionId))
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_ACTION = "action"
        const val EXTRA_OPTION_ID = "optionId"
    }
}
