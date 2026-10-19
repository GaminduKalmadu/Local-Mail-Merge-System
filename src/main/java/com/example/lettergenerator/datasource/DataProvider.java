package com.example.lettergenerator.datasource;

import com.example.lettergenerator.model.DataRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface DataProvider {
    List<DataRecord> loadData(InputStream input) throws IOException;
    List<String> columns();
}
