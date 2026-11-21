package com.library.app.ui.screens.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.library.app.data.AppLock
import com.library.app.data.crypto.AuthOutcome
import com.library.app.data.crypto.BiometricGate
import com.library.app.i18n.tr
import com.library.app.ui.SessionState
import com.library.app.ui.components.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 锁屏页。两种进入方式各走一套：
 * - 应用密码：只有输入框，确认靠下面的「解锁」按钮（不拿回车当确认）。
 * - 手机凭证：进页面就弹系统验证，指纹或手机锁屏密码都在系统那边输。
 */
@Composable
fun LockScreen(session: SessionState, lock: AppLock) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var askForgot by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var newConfirm by remember { mutableStateOf("") }

    val deviceReady = remember(activity) {
        activity?.let { BiometricGate.canAuthenticate(it) } ?: false
    }

    suspend fun submitPassword() {
        if (busy || password.isBlank()) return
        busy = true
        hint = null
        val ok = withContext(Dispatchers.Default) { lock.verifyPassword(password) }
        busy = false
        if (ok) {
            session.unlock()
        } else {
            hint = "密码不对，再试一次".tr()
            password = ""
        }
    }

    suspend fun attemptDevice() {
        val host = activity ?: return
        if (busy) return
        busy = true
        hint = null
        when (val outcome = BiometricGate.authenticate(
            activity = host,
            title = "解锁资料库".tr(),
            subtitle = "使用指纹或手机锁屏密码".tr(),
        )) {
            is AuthOutcome.Success -> session.unlock()
            AuthOutcome.Cancelled -> hint = "已取消验证，点下面的按钮重试".tr()
            is AuthOutcome.Failed -> hint = outcome.message?.toString() ?: "验证失败，请重试".tr()
        }
        busy = false
    }

    // 手机凭证模式：进来就弹一次
    LaunchedEffect(Unit) {
        if (lock.mode == AppLock.Mode.DEVICE && deviceReady) attemptDevice()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(48.dp))

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text("资料库已锁定".tr(), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))

            val usingPassword = lock.mode == AppLock.Mode.PASSWORD || resetting || !deviceReady

            Text(
                text = when {
                    resetting -> "验证通过，设一个新密码".tr()
                    usingPassword -> "输入应用密码后查看内容".tr()
                    else -> "验证指纹或锁屏密码后查看内容".tr()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            when {
                resetting -> ResetFields(
                    password = newPassword,
                    confirm = newConfirm,
                    onPassword = {
                        newPassword = it
                        hint = null
                    },
                    onConfirm = {
                        newConfirm = it
                        hint = null
                    },
                    onCancel = {
                        resetting = false
                        newPassword = ""
                        newConfirm = ""
                        hint = null
                    },
                    onSubmit = {
                        when {
                            newPassword.length < AppLock.MIN_LENGTH -> hint = "密码至少 4 位".tr()
                            newPassword != newConfirm -> hint = "两次输入的密码不一致".tr()
                            else -> scope.launch {
                                busy = true
                                withContext(Dispatchers.Default) { lock.resetToPasswordMode(newPassword) }
                                busy = false
                                session.unlock()
                            }
                        }
                    },
                    busy = busy,
                )

                usingPassword -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            hint = null
                        },
                        label = { Text("应用密码".tr()) },
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        // 键盘上的确认键和解锁按钮走同一段逻辑
                        keyboardActions = KeyboardActions(onDone = {
                            focus.clearFocus()
                            scope.launch { submitPassword() }
                        }),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    if (visible) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = if (visible) "隐藏密码".tr() else "显示密码".tr(),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { scope.launch { submitPassword() } },
                        enabled = !busy && password.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("解锁".tr())
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { askForgot = true }) { Text("忘记密码".tr()) }
                }

                else -> {
                    Button(
                        onClick = { scope.launch { attemptDevice() } },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("点击验证".tr())
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 不能直接跳去设新密码，否则这里就成了绕过锁屏的后门
                    TextButton(onClick = {
                        scope.launch {
                            val host = activity ?: return@launch
                            hint = null
                            when (BiometricGate.authenticate(
                                activity = host,
                                title = "验证手机凭证".tr(),
                                subtitle = "确认是本人后再改密码".tr(),
                            )) {
                                is AuthOutcome.Success -> {
                                    resetting = true
                                    hint = null
                                }
                                AuthOutcome.Cancelled ->
                                    hint = "已取消验证，点下面的按钮重试".tr()
                                is AuthOutcome.Failed ->
                                    hint = "验证没有通过".tr()
                            }
                        }
                    }) { Text("改用应用密码".tr()) }
                }
            }

            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = hint ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            if (lock.mode == AppLock.Mode.DEVICE && !deviceReady && !resetting) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "这台手机的锁屏密码已经不可用，可以改用应用密码继续。".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    if (askForgot) {
        AlertDialog(
            onDismissRequest = { askForgot = false },
            title = { Text("忘记应用密码？".tr()) },
            text = {
                Text(
                    if (deviceReady) {
                        "可以用手机的指纹或锁屏密码验证一次，然后重设应用密码。资料不会丢。".tr()
                    } else {
                        "这台手机没有设置锁屏密码，也没有别的验证方式，只能清除应用数据后重新开始。".tr()
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askForgot = false
                        if (deviceReady) {
                            scope.launch {
                                when (BiometricGate.authenticate(activity!!, "重设应用密码".tr(), "先验证手机身份".tr())) {
                                    is AuthOutcome.Success -> resetting = true
                                    else -> hint = "验证没有通过".tr()
                                }
                            }
                        }
                    },
                ) {
                    Text(if (deviceReady) "验证后重设".tr() else "知道了".tr())
                }
            },
            dismissButton = {
                TextButton(onClick = { askForgot = false }) { Text("取消".tr()) }
            },
        )
    }
}

@Composable
private fun ResetFields(
    password: String,
    confirm: String,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    busy: Boolean,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        label = { Text("新密码".tr()) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = confirm,
        onValueChange = onConfirm,
        label = { Text("再次输入".tr()) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focus.clearFocus()
            onSubmit()
        }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmit,
        enabled = !busy && password.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(15.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("确认".tr())
        }
    }
    TextButton(onClick = onCancel) { Text("返回".tr()) }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
