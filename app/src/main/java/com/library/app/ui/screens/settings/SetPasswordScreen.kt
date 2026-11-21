package com.library.app.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
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
import com.library.app.ui.LocalAppLock
import com.library.app.ui.components.AppIcons
import com.library.app.ui.components.LibraryTopBar
import com.library.app.ui.screens.lock.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 重置完之后停在哪：应用密码，或者手机的指纹 / 锁屏密码。 */
private enum class Done { PASSWORD, DEVICE }

/**
 * 重置密码页：验证过一次之后才进得来，这里只收新密码。
 *
 * 确认只认下面的「保存」，回车不提交；存完把进入方式切成应用密码。
 * 想反悔、改回手机凭证也在这里，省得再开一个「进入方式」。
 */
@Composable
fun SetPasswordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lock = LocalAppLock.current
    val scope = rememberCoroutineScope()

    var fresh by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Done?>(null) }
    val focus = LocalFocusManager.current

    // 从手机凭证切过来的话要说一句，不然用户不知道下次要输这个密码
    val switching = remember { lock.mode != AppLock.Mode.PASSWORD }
    val host = remember(context) { context.findActivity() }
    val canDevice = remember(host) { host != null && BiometricGate.canAuthenticate(host) }

    /** 保存按钮和键盘上的确认键走同一段逻辑。 */
    fun save() {
        when {
            fresh.length < AppLock.MIN_LENGTH -> error = "密码至少 4 位".tr()
            fresh != again -> error = "两次输入的密码不一致".tr()
            else -> scope.launch {
                busy = true
                error = null
                withContext(Dispatchers.Default) { lock.useAppPassword(fresh) }
                busy = false
                fresh = ""
                again = ""
                done = Done.PASSWORD
            }
        }
    }

    fun useDeviceCredential() {
        val activity = host ?: return
        scope.launch {
            when (BiometricGate.authenticate(
                activity = activity,
                title = "验证手机凭证".tr(),
                subtitle = "确认是本人后再切换".tr(),
            )) {
                is AuthOutcome.Success -> {
                    lock.useDeviceCredential()
                    done = Done.DEVICE
                }
                AuthOutcome.Cancelled -> Unit
                is AuthOutcome.Failed -> error = "验证没有通过".tr()
            }
        }
    }

    Scaffold(
        topBar = { LibraryTopBar(title = "重置密码".tr(), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            val finished = done
            if (finished != null) {
                Spacer(Modifier.height(48.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (finished == Done.DEVICE) "已改用手机密码凭证".tr() else "密码已更新".tr(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (finished == Done.DEVICE) "下次打开应用时用指纹或手机锁屏密码".tr()
                    else "下次打开应用时输入这个密码".tr(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text("完成".tr())
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Text(
                text = "输入两遍新密码".tr(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = fresh,
                onValueChange = {
                    fresh = it
                    error = null
                },
                label = { Text("新密码".tr()) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) AppIcons.EyeOff else AppIcons.Eye,
                            contentDescription = if (visible) "隐藏密码".tr() else "显示密码".tr(),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = again,
                onValueChange = {
                    again = it
                    error = null
                },
                label = { Text("再次输入".tr()) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus()
                    save()
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { save() },
                enabled = !busy && fresh.isNotBlank() && again.isNotBlank(),
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
                    Text("保存".tr())
                }
            }

            if (switching) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "保存后进入应用改用这个密码".tr(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (canDevice) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { useDeviceCredential() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("改用手机密码凭证".tr())
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
