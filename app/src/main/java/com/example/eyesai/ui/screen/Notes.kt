package com.example.eyesai.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.eyesai.ui.MainViewModel
import com.example.eyesai.ui.components.AppBar

@Composable
fun NotesScreen(
    navController: NavHostController,
    viewModel: MainViewModel = viewModel()
) {
    val notes by viewModel.notes.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Display Notes
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(notes) { note ->
                NoteItem(note = note, onDelete = { viewModel.deleteNote(note) })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Button(
            onClick = { viewModel.addNote("New Note") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Note")
        }
    }
}

@Composable
fun NoteItem(note: String, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = note, style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onDelete) {
                Text("Delete")
            }
        }
    }
}