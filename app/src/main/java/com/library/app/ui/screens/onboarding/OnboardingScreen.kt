package com.library.app.ui.screens.onboarding

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.library.app.data.AppLock
import com.library.app.data.crypto.AuthOutcome
import com.library.app.data.crypto.BiometricGate
import com.library.app.i18n.AppLanguage
import com.library.app.i18n.Lang
import com.library.app.i18n.tr
import com.library.app.ui.LocalBranding
import com.library.app.ui.components.AppIcons
import com.library.app.ui.screens.lock.findActivity
import com.library.app.ui.screens.settings.AvatarCropDialog
import com.library.app.ui.screens.settings.loadAvatarSource
import kotlinx.coroutines.launch

private enum class Step { LANGUAGE, PROFILE, SECURITY, GUIDE }

/**
 * 首次进入的四步引导：语言 → 头像昵称 → 锁屏方式 → 使用引导。
 *
 * 也用来从设置里「再看一遍」：那时直接落在最后一步，关掉就返回。
 */
@Composable
fun OnboardingScreen(
    lock: AppLock,
    replay: Boolean = false,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val branding = LocalBranding.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(if (replay) Step.GUIDE else Step.LANGUAGE) }

    // 资料库的锁屏方式
    var mode by remember { mutableStateOf(AppLock.Mode.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val pager = rememberPagerState(pageCount = { 4 })

    val deviceSecure = remember {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguard.isDeviceSecure
    }
    val hasBiometric = remember { activity?.let { BiometricGate.hasEnrolledBiometric(it) } ?: false }

    fun finish(mode: AppLock.Mode) {
        lock.finishOnboarding(mode)
        onDone()
    }

    fun setupPassword() {
        error = when {
            password.length < AppLock.MIN_LENGTH -> "密码至少 4 位".tr()
            password != confirm -> "两次输入的密码不一致".tr()
            else -> null
        }
        if (error != null) return
        // PBKDF2 要算一下，别卡住主线程
        scope.launch {
            busy = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                lock.setPassword(password)
            }
            busy = false
            step = Step.GUIDE
        }
    }

    fun verifyDevice() {
        val host = activity ?: return
        if (busy) return
        scope.launch {
            busy = true
            when (BiometricGate.authenticate(host, "确认解锁方式".tr(), "用指纹或手机锁屏密码验证一次".tr())) {
                is AuthOutcome.Success -> {
                    busy = false
                    lock.useDeviceCredential()
                    step = Step.GUIDE
                }
                AuthOutcome.Cancelled -> {
                    busy = false
                    error = "已取消验证，可以再试一次".tr()
                }
                is AuthOutcome.Failed -> {
                    busy = false
                    error = "验证没有通过，换一种方式也可以".tr()
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            if (!replay) {
                Spacer(Modifier.height(20.dp))
                StepBar(index = step.ordinal, count = Step.entries.size)
            } else {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDone) { Text("关闭".tr()) }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (step) {
                    Step.LANGUAGE -> LanguageStep(
                        onPick = { Lang.select(it) },
                    )

                    Step.PROFILE -> ProfileStep(
                        nickname = branding.nickname,
                        onNickname = branding::updateNickname,
                        onPickAvatar = { cropSource = it },
                    )

                    Step.SECURITY -> SecurityStep(
                        onSubmit = { setupPassword() },
                        mode = mode,
                        onModeChange = {
                            mode = it
                            error = null
                        },
                        deviceSecure = deviceSecure,
                        hasBiometric = hasBiometric,
                        password = password,
                        onPasswordChange = {
                            password = it
                            error = null
                        },
                        confirm = confirm,
                        onConfirmChange = {
                            confirm = it
                            error = null
                        },
                        error = error,
                    )

                    Step.GUIDE -> GuideStep(pager = pager)
                }
            }

            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                if (step == Step.SECURITY && error != null) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                val primary: Pair<String, () -> Unit> = when (step) {
                    Step.LANGUAGE -> "继续".tr() to { step = Step.PROFILE }
                    Step.PROFILE -> "继续".tr() to { step = Step.SECURITY }
                    Step.SECURITY -> if (mode == AppLock.Mode.PASSWORD) {
                        "确认".tr() to { setupPassword() }
                    } else {
                        "完成".tr() to { verifyDevice() }
                    }
                    Step.GUIDE -> when {
                        replay -> "关闭".tr() to onDone
                        pager.currentPage < 3 -> "下一页".tr() to {
                            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                        else -> "开始使用".tr() to { finish(mode) }
                    }
                }

                Button(
                    onClick = primary.second,
                    enabled = !busy && !(step == Step.SECURITY && mode == AppLock.Mode.PASSWORD && password.isBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(primary.first, fontSize = 16.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val showBack = step != Step.LANGUAGE && !replay &&
                        !(step == Step.GUIDE && pager.currentPage == 0)
                    if (showBack) {
                        TextButton(onClick = {
                            if (step == Step.GUIDE) {
                                scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                            } else {
                                step = Step.entries[(step.ordinal - 1).coerceAtLeast(0)]
                            }
                        }) { Text("上一步".tr()) }
                    }
                    // 不写「跳过」：李跳跳这类自动跳广告的辅助功能会照着这个词点，
                    // 引导会被一路自动点过去。
                    if (step == Step.PROFILE) {
                        TextButton(onClick = { step = Step.SECURITY }) { Text("稍后设置".tr()) }
                    }
                    if (step == Step.LANGUAGE) {
                        TextButton(onClick = { step = Step.PROFILE }) { Text("稍后设置".tr()) }
                    }
                }
            }
        }
    }

    cropSource?.let { source ->
        AvatarCropDialog(
            source = source,
            onCancel = { cropSource = null },
            onDone = { square ->
                branding.update(square)
                cropSource = null
            },
        )
    }
}

// ---------------------------------------------------------------- 顶部步骤条

@Composable
private fun StepBar(index: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .weight(if (i == index) 2f else 1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= index) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------- 1 语言

@Composable
private fun LanguageStep(onPick: (AppLanguage) -> Unit) {
    val current = Lang.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(40.dp))
        IconTile(AppIcons.Globe)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "选择语言".tr(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "之后可以在设置里随时改".tr(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        AppLanguage.entries.forEach { language ->
            ChoiceCard(
                selected = current == language,
                title = language.label,
                subtitle = null,
                icon = null,
                onClick = { onPick(language) },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------------------------------------------------------- 2 头像 + 昵称

@Composable
private fun ProfileStep(
    nickname: String,
    onNickname: (String) -> Unit,
    onPickAvatar: (android.graphics.Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logo = LocalBranding.current.logo
    var hint by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = loadAvatarSource(context, uri)
            if (bitmap == null) hint = "这张图片读不出来，换一张试试".tr() else onPickAvatar(bitmap)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "设置头像和昵称".tr(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "会显示在左上角，点一下就能进设置".tr(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "库".tr(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = onNickname,
            label = { Text("昵称".tr()) },
            singleLine = true,
            placeholder = { Text("User") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------- 3 锁屏方式

@Composable
private fun SecurityStep(
    onSubmit: () -> Unit,
    mode: AppLock.Mode,
    onModeChange: (AppLock.Mode) -> Unit,
    deviceSecure: Boolean,
    hasBiometric: Boolean,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
    error: String?,
) {
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "选择加密方式".tr(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))

        ChoiceCard(
            selected = mode == AppLock.Mode.PASSWORD,
            title = "应用密码".tr(),
            subtitle = "自己设一个密码，每次进入时输入".tr(),
            icon = AppIcons.Key,
            onClick = { onModeChange(AppLock.Mode.PASSWORD) },
        )
        if (mode == AppLock.Mode.PASSWORD) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("设置密码".tr()) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = onConfirmChange,
                label = { Text("再次输入".tr()) },
                singleLine = true,
                isError = error != null && password != confirm,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus()
                    onSubmit()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "密码忘了只能重来，资料还在，但已保存的登录密码要重新录".tr(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        ChoiceCard(
            selected = mode == AppLock.Mode.DEVICE,
            enabled = deviceSecure,
            title = "手机密码凭证".tr(),
            subtitle = when {
                !deviceSecure -> "这台手机还没有锁屏密码，先在系统设置里设置，或改用应用密码".tr()
                hasBiometric -> "用指纹，或手机自己的锁屏密码".tr()
                else -> "用手机自己的锁屏密码".tr()
            },
            icon = AppIcons.Phone,
            onClick = { if (deviceSecure) onModeChange(AppLock.Mode.DEVICE) },
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------- 4 使用引导

@Composable
private fun GuideStep(pager: androidx.compose.foundation.pager.PagerState) {
    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> WhatPage()

                1 -> TextFirstPage(
                    title = "设置在这里".tr(),
                    body = "左上角是头像和昵称，点一下进设置：换头像、改昵称、切换语言、备份数据都在那儿。".tr(),
                    art = { SettingsArt() },
                )

                2 -> ArtMiddlePage(
                    title = "关于资料库".tr(),
                    body = "在导入资料时应用仅记录索引，导入的文件仍然在原位".tr(),
                    art = { ImportArt() },
                )

                else -> ThanksPage()
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (pager.currentPage == i) 18.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (pager.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
    }
}

/** 第一页：三个模块从上往下排，各带一句说明。 */
@Composable
private fun WhatPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 28.dp),
    ) {
        Text(
            text = "我能做什么？".tr(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))
        ModuleRow(
            icon = AppIcons.Archive,
            name = "资料库".tr(),
            summary = "将资料进行归档，整理，便于查询".tr(),
        )
        Spacer(Modifier.height(10.dp))
        ModuleRow(
            icon = AppIcons.List,
            name = "记录".tr(),
            summary = "随手记录待办事项，甚至是当前的状态，心情，一段文字，对未来的规划".tr(),
        )
        Spacer(Modifier.height(10.dp))
        ModuleRow(
            icon = AppIcons.Key,
            name = "密码".tr(),
            summary = "存储您在各个平台的登录密码，同样便于查询".tr(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 文字在上、插图在下。 */
@Composable
private fun TextFirstPage(
    title: String,
    body: String,
    art: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 28.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        ArtBox(art)
        Spacer(Modifier.height(24.dp))
    }
}

/** 文字在上、插图在中间、再一段文字收尾。 */
@Composable
private fun ArtMiddlePage(
    title: String,
    body: String,
    art: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 28.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        ArtBox(art)
        Spacer(Modifier.height(20.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 最后一页：对号 + 道谢 + 不联网。 */
@Composable
private fun ThanksPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = AppIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "感谢完成引导".tr(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "本应用无需联网，以最大程度保证您的数据安全".tr(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ArtBox(art: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { art() }
}

@Composable
private fun ModuleRow(icon: ImageVector, name: String, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 引导里的小插图

@Composable
private fun SettingsArt() {
    val nickname = LocalBranding.current.nickname
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("库".tr(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Text(nickname, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Icon(AppIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Icon(AppIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("头像 / 昵称 / 语言 / 备份".tr(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportArt() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(6.dp))
            Text("相册 / 下载".tr(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(18.dp))
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text("保留层级".tr(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- 小组件

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val border = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected && enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .border(if (selected && enabled) 2.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle == null) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
        if (selected && enabled) {
            Icon(
                AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
