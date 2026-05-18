package com.codepilot.module.rag.service;

import com.codepilot.module.rag.dto.RuleSearchRequest;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;

import java.util.ArrayList;
import java.util.List;

public interface RuleSearchService {

    RuleSearchResponse search(RuleSearchRequest request);

    default RuleSearchResponse searchByTypes(String query, Integer topK, List<String> types) {
        if (types == null || types.isEmpty()) {
            RuleSearchRequest request = new RuleSearchRequest();
            request.setQuery(query);
            request.setTopK(topK);
            return search(request);
        }

        List<RuleSearchRecord> records = new ArrayList<>();
        for (String type : types) {
            RuleSearchRequest request = new RuleSearchRequest();
            request.setQuery(query);
            request.setTopK(topK);
            request.setType(type);
            RuleSearchResponse response = search(request);
            if (response != null && response.getRecords() != null) {
                records.addAll(response.getRecords());
            }
        }
        return new RuleSearchResponse(records);
    }
}
