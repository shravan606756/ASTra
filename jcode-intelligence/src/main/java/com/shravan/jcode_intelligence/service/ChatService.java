package com.shravan.jcode_intelligence.service;

import com.shravan.jcode_intelligence.dto.request.ChatRequest;
import com.shravan.jcode_intelligence.dto.response.ChatResponse;

public interface ChatService {

    ChatResponse answer(ChatRequest request);
}

