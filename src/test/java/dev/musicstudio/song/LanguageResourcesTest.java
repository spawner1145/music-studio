package dev.musicstudio.song;

import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageResourcesTest {
    private Map<String,String> language(String code) throws Exception {
        return ProjectStore.GSON.fromJson(Files.readString(Path.of("src/main/resources/assets/musicstudio/lang/"+code+".json")),new TypeToken<Map<String,String>>(){}.getType());
    }
    @Test void bothLanguagesHaveMatchingKeysAndCompatiblePlaceholders() throws Exception {
        var en=language("en_us");var zh=language("zh_cn");assertEquals(en.keySet(),zh.keySet());assertTrue(en.size()>150);
        Pattern format=Pattern.compile("%[sdif]");
        for(String key:en.keySet()){
            assertFalse(en.get(key).isBlank(),key);assertFalse(zh.get(key).isBlank(),key);
            var enArgs=format.matcher(en.get(key)).results().map(MatchResult::group).toList();var zhArgs=format.matcher(zh.get(key)).results().map(MatchResult::group).toList();assertEquals(enArgs,zhArgs,key);
            var args=enArgs.stream().map(x->(Object)7).toArray();assertDoesNotThrow(()->String.format(Locale.ROOT,en.get(key),args),key);assertDoesNotThrow(()->String.format(Locale.ROOT,zh.get(key),args),key);
        }
    }
    @Test void allLiteralUiAndDomainErrorKeysArePresent() throws Exception {
        var language=language("en_us");Pattern keys=Pattern.compile("(?:tr\\(\"|new StudioException\\(\"|named\\(\")([a-z][a-z_.]+)\"");
        try(var files=Files.walk(Path.of("src/main/java/dev/musicstudio"))){
            for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
                String source=Files.readString(file);Matcher match=keys.matcher(source);
                while(match.find()){String key=match.group(1);if(!key.endsWith("."))assertTrue(language.containsKey("musicstudio."+key),file+": "+key);}
                Matcher errors=Pattern.compile("\"(error\\.[a-z_]+)\"").matcher(source);while(errors.find())assertTrue(language.containsKey("musicstudio."+errors.group(1)),file+": "+errors.group(1));
            }
        }
    }
}
