package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// One candidate from item photo recognition, best first. item is the same option the item dropdown
// returns (so a form can select it directly); similarity is cosine similarity 0..1 to the item's
// closest reference image; imagePath is that image, for showing it next to the user's photo.
@Getter
@AllArgsConstructor
public class ItemRecognitionMatchResponse {
    private ItemLookupResponse item;
    private double similarity;
    private String imagePath;
}
