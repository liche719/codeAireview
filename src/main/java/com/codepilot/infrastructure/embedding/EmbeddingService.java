package com.codepilot.infrastructure.embedding;

import java.util.List;

public interface EmbeddingService {

    List<Float> embed(String text);
}
