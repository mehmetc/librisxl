String fixBalancedBrackets(String s) {
    if (s.size() > 1 &&
            s.startsWith('[') &&
            s.count('[') > s.count(']')) {
        s = s[1..-1]
    } else if (s.size() > 1 &&
            s.endsWith(']') &&
            s.count('[') < s.count(']')) {
        s = s[0..-2]
    }
    return s
}

// Verify behaviour
[
    ['[a','a'],
    ['a]','a'],
    ['[a] b]','[a] b'],
    ['[a [b]','a [b]'],
    ['[a] b', '[a] b'],
    ['a [b]', 'a [b]'],
    ['[a] [b]', '[a] [b]']
].each {
    def (before, after) = it
    assert fixBalancedBrackets(before) == after
}


SKIP_KEYS = [ID, TYPE] as Set

void findAndFixValuesInData(data, obj) {
    if (obj instanceof List) {
        obj.eachWithIndex { value, int i ->
            fixValueInData(data, obj, i, value)
        }
    } else if (obj instanceof Map) {
        obj.each { key, value ->
            if (key in SKIP_KEYS) return
            fixValueInData(data, obj, key, value)
        }
    }
}

void fixValueInData(data, container, key, value) {
    if (value instanceof String) {
        def fixed = fixBalancedBrackets(value)
        if (fixed != value) {
            container[key] = fixed
            data.scheduleSave(loud: false)
        }
    } else {
        findAndFixValuesInData(data, value)
    }
}

selectBySqlWhere(''' collection IN ('auth', 'bib', 'hold') ''') { data ->
    // Skipping record on the assumption it contains no unbalanced brackets
    data.graph[1..-1].each {
        findAndFixValuesInData(data, it)
    }
}
