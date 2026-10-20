package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.artflow.studio.domain.model.brush.BrushTexture
import com.artflow.studio.domain.model.brush.TextureLibrary

/**
 * Brush texture selector panel for choosing and managing brush textures
 * Implements Phase 10: Brush Textures & Stamps - Texture library UI
 */
@Composable
fun BrushTextureSelector(
    textureLibrary: TextureLibrary,
    selectedTextureId: String?,
    onTextureSelected: (BrushTexture) -> Unit,
    onImportTexture: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf<BrushTexture.TextureCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Filter textures based on search and category
    val filteredTextures = remember(textureLibrary, searchQuery, selectedCategory) {
        val searched = if (searchQuery.isNotBlank()) {
            textureLibrary.searchTextures(searchQuery)
        } else {
            textureLibrary.textures
        }
        
        if (selectedCategory != null) {
            searched.filter { it.category == selectedCategory }
        } else {
            searched
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with search and import
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Brush Textures",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            IconButton(onClick = onImportTexture) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Add,
                    contentDescription = "Import Texture",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search textures...") },
            leadingIcon = {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Search,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )
        
        // Category filter chips
        CategoryFilterRow(
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it }
        )
        
        // Recently used section (if there are any)
        if (textureLibrary.recentlyUsed.isNotEmpty() && searchQuery.isBlank() && selectedCategory == null) {
            RecentlyUsedSection(
                recentlyUsedIds = textureLibrary.recentlyUsed,
                allTextures = textureLibrary.textures,
                selectedTextureId = selectedTextureId,
                onTextureSelected = onTextureSelected
            )
        }
        
        // Favorites section (if there are any)
        if (textureLibrary.favorites.isNotEmpty() && searchQuery.isBlank() && selectedCategory == null) {
            FavoritesSection(
                favoriteIds = textureLibrary.favorites,
                allTextures = textureLibrary.textures,
                selectedTextureId = selectedTextureId,
                onTextureSelected = onTextureSelected
            )
        }
        
        // All textures grid
        Text(
            text = if (selectedCategory != null) "${selectedCategory?.name ?: "All"} Textures" 
                   else "All Textures (${filteredTextures.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 80.dp),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredTextures, key = { it.id }) { texture ->
                TextureGridItem(
                    texture = texture,
                    isSelected = texture.id == selectedTextureId,
                    isFavorite = texture.id in textureLibrary.favorites,
                    onClick = { onTextureSelected(texture) },
                    onToggleFavorite = { /* TODO: Implement favorite toggle */ }
                )
            }
        }
        
        // Empty state
        if (filteredTextures.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Text(
                        text = "No textures found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    if (searchQuery.isNotBlank()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("Clear search")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Category filter chip row
 */
@Composable
private fun CategoryFilterRow(
    selectedCategory: BrushTexture.TextureCategory?,
    onCategorySelected: (BrushTexture.TextureCategory?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategorySelected(null) },
            label = { Text("All") },
            modifier = Modifier.weight(1f)
        )
        
        // Show most common categories as quick filters
        val quickCategories = listOf(
            BrushTexture.TextureCategory.PAPER,
            BrushTexture.TextureCategory.GRAIN,
            BrushTexture.TextureCategory.PATTERNS
        )
        
        quickCategories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { 
                    Text(
                        text = category.name.lowercase().replaceFirstChar { it.uppercase() },
                        maxLines = 1
                    ) 
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Recently used textures section
 */
@Composable
private fun RecentlyUsedSection(
    recentlyUsedIds: List<String>,
    allTextures: List<BrushTexture>,
    selectedTextureId: String?,
    onTextureSelected: (BrushTexture) -> Unit
) {
    val recentTextures = recentlyUsedIds
        .mapNotNull { id -> allTextures.find { it.id == id } }
        .take(5)
    
    if (recentTextures.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recently Used",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recentTextures.forEach { texture ->
                    TextureThumbnail(
                        texture = texture,
                        isSelected = texture.id == selectedTextureId,
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onTextureSelected(texture) }
                    )
                }
            }
        }
    }
}

/**
 * Favorite textures section
 */
@Composable
private fun FavoritesSection(
    favoriteIds: Set<String>,
    allTextures: List<BrushTexture>,
    selectedTextureId: String?,
    onTextureSelected: (BrushTexture) -> Unit
) {
    val favoriteTextures = favoriteIds
        .mapNotNull { id -> allTextures.find { it.id == id } }
    
    if (favoriteTextures.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Favorites",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 60.dp),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favoriteTextures, key = { it.id }) { texture ->
                    TextureGridItem(
                        texture = texture,
                        isSelected = texture.id == selectedTextureId,
                        isFavorite = true,
                        onClick = { onTextureSelected(texture) },
                        onToggleFavorite = { /* TODO: Implement */ }
                    )
                }
            }
        }
    }
}

/**
 * Individual texture grid item
 */
@Composable
private fun TextureGridItem(
    texture: BrushTexture,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Texture thumbnail
            TextureThumbnail(
                texture = texture,
                isSelected = isSelected,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            
            // Texture name (truncated)
            Text(
                text = texture.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        // Favorite indicator
        if (isFavorite) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = Color.Red,
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Texture thumbnail preview
 */
@Composable
private fun TextureThumbnail(
    texture: BrushTexture,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.LightGray)
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
    ) {
        // In production, this would load the actual texture thumbnail
        AsyncImage(
            model = texture.thumbnailPath ?: texture.filePath,
            contentDescription = texture.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
