package com.gatto.wise.matcher;

import java.util.Set;

public record SanctionedEntity(String id, String fullName, Set<String> aliases) { }
