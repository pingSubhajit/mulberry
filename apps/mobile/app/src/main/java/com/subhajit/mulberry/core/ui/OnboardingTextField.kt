package com.subhajit.mulberry.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.ui.theme.MulberryInk
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily

private val OnboardingFieldBackground = Color(0xFFF3F3F3)
private val OnboardingPlaceholderColor = Color.Black.copy(alpha = 0.60f)

@Composable
fun OnboardingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    } else {
        modifier.fillMaxWidth()
    }

    if (readOnly && onClick != null) {
        OnboardingTextFieldContainer(
            value = value,
            label = label,
            placeholder = placeholder,
            modifier = clickableModifier
        )
        return
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = TextStyle(
            fontFamily = PoppinsFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            color = MulberryInk
        ),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = keyboardActions,
        modifier = clickableModifier,
        decorationBox = { innerTextField ->
            OnboardingTextFieldContainer(
                value = value,
                label = label,
                placeholder = placeholder,
                innerTextField = innerTextField
            )
        }
    )
}

@Composable
private fun OnboardingTextFieldContainer(
    value: String,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    innerTextField: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(OnboardingFieldBackground, RoundedCornerShape(15.38.dp))
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = Color.Black,
            style = TextStyle(
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = OnboardingPlaceholderColor,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                )
            } else if (innerTextField == null) {
                Text(
                    text = value,
                    color = MulberryInk,
                    style = TextStyle(
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                )
            }
            innerTextField?.invoke()
        }
    }
}
