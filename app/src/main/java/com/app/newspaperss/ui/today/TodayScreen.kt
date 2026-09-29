package com.app.newspaperss.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TodayScreen(today: LocalDate = LocalDate.now()) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Masthead(today)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your first edition will appear here.", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun Masthead(date: LocalDate) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("newspaperss", style = MaterialTheme.typography.displaySmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 2.dp)
        Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), style = MaterialTheme.typography.labelLarge)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}
