package com.legacy.modernizer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodInfo {
    private String name;
    private String returnType;
    private List<String> parameterTypes;
}
