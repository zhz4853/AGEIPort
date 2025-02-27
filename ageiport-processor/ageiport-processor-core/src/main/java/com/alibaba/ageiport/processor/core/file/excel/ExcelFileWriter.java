package com.alibaba.ageiport.processor.core.file.excel;

import com.alibaba.ageiport.common.io.FastByteArrayOutputStream;
import com.alibaba.ageiport.common.utils.IOUtils;
import com.alibaba.ageiport.common.utils.JsonUtil;
import com.alibaba.ageiport.ext.arch.ExtensionLoader;
import com.alibaba.ageiport.processor.core.AgeiPort;
import com.alibaba.ageiport.processor.core.AgeiPortOptions;
import com.alibaba.ageiport.processor.core.constants.ConstValues;
import com.alibaba.ageiport.processor.core.model.core.ColumnHeader;
import com.alibaba.ageiport.processor.core.model.core.ColumnHeaders;
import com.alibaba.ageiport.processor.core.spi.file.DataGroup;
import com.alibaba.ageiport.processor.core.spi.file.FileContext;
import com.alibaba.ageiport.processor.core.spi.file.FileWriter;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.context.WriteContext;
import com.alibaba.excel.enums.WriteTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterSheetBuilder;
import com.alibaba.excel.write.handler.WriteHandler;
import com.alibaba.excel.write.metadata.WriteSheet;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author lingyi
 */
public class ExcelFileWriter implements FileWriter {

    private AgeiPort ageiPort;

    private ColumnHeaders columnHeaders;

    private ExcelWriter excelWriter;

    private FileContext fileContext;

    private List<WriteHandler> writeHandlers;

    private Map<Integer, WriteSheet> writeSheetMap;

    private Map<String, Integer> sheetNameNoMap;

    public ExcelFileWriter(AgeiPort ageiPort, ColumnHeaders columnHeaders, FileContext fileContext) {

        this.ageiPort = ageiPort;
        this.columnHeaders = columnHeaders;
        this.fileContext = fileContext;
        this.writeHandlers = new ArrayList<>();
        this.writeSheetMap = new HashMap<>();
        this.sheetNameNoMap = new HashMap<>();

        AgeiPortOptions options = ageiPort.getOptions();
        Map<String, Map<String, String>> spiConfigs = options.getSpiConfigs();
        Map<String, String> providerConfigs = spiConfigs.get("ExcelWriteHandlerProvider");
        String configAsJson = JsonUtil.toJsonString(providerConfigs);
        ExcelWriteHandlerProviderSpiConfig providerSpiConfig = JsonUtil.toObject(configAsJson, ExcelWriteHandlerProviderSpiConfig.class);

        for (String extensionName : providerSpiConfig.getExtensionNames()) {
            ExtensionLoader<ExcelWriteHandlerProvider> extensionLoader = ExtensionLoader.getExtensionLoader(ExcelWriteHandlerProvider.class);
            ExcelWriteHandlerProvider handlerProvider = extensionLoader.getExtension(extensionName);
            this.writeHandlers.addAll(handlerProvider.provide(ageiPort, columnHeaders, fileContext));
        }

        FastByteArrayOutputStream output = new FastByteArrayOutputStream(10240);
        this.excelWriter = EasyExcel.write(output).build();
    }

    @Override
    public void write(DataGroup fileData) {
        for (DataGroup.Data data : fileData.getData()) {
            Integer sheetNo;
            String sheetName = ConstValues.DEFAULT_SHEET_NAME;

            Map<String, String> meta = data.getMeta();
            if (meta == null || !meta.containsKey(ExcelConstants.sheetNameKey)) {
                sheetName = data.getCode() == null ? sheetName : data.getCode();
            } else {
                sheetName = meta.get(ExcelConstants.sheetNameKey);
            }

            if (meta == null || !meta.containsKey(ExcelConstants.sheetNoKey)) {
                if (!sheetNameNoMap.containsKey(sheetName)) {
                    int size = sheetNameNoMap.size();
                    sheetNameNoMap.put(sheetName, size);
                }
                sheetNo = sheetNameNoMap.get(sheetName);
            } else {
                sheetNo = Integer.parseInt(meta.get(ExcelConstants.sheetNoKey));
            }

            if (!this.writeSheetMap.containsKey(sheetNo)) {
                List<List<String>> head = columnHeaders.getColumnHeaders().stream()
                        .filter(s -> !s.getIgnoreHeader())
                        .filter(s -> s.getGroupIndex().equals(sheetNo) || s.getGroupIndex().equals(-1))
                        .map(ColumnHeader::getHeaderName)
                        .collect(Collectors.toList());

                ExcelWriterSheetBuilder sheetBuilder = EasyExcel.writerSheet()
                        .sheetNo(sheetNo)
                        .sheetName(sheetName)
                        .needHead(true)
                        .head(head);

                for (WriteHandler writeHandler : this.writeHandlers) {
                    sheetBuilder.registerWriteHandler(writeHandler);
                }
                WriteSheet writeSheet = sheetBuilder.build();
                writeSheetMap.put(sheetNo, writeSheet);

                excelWriter.writeContext().currentSheet(writeSheet, WriteTypeEnum.ADD);
            }

            WriteSheet writeSheet = writeSheetMap.get(sheetNo);
            List<List<Object>> lines = resolve(columnHeaders, data, sheetNo);
            excelWriter.write(lines, writeSheet);
        }
    }

    @Override
    public InputStream finish() {
        WriteContext writeContext = excelWriter.writeContext();
        FastByteArrayOutputStream outputStream = (FastByteArrayOutputStream) writeContext.writeWorkbookHolder().getOutputStream();
        excelWriter.finish();
        return outputStream.getInputStream();
    }


    @Override
    public void close() {
        IOUtils.closeQuietly(excelWriter);
    }

    List<List<Object>> resolve(ColumnHeaders columnHeaders, DataGroup.Data groupData, Integer groupIndex) {
        List<DataGroup.Item> items = groupData.getItems();
        List<List<Object>> data = new ArrayList<>(items.size());
        for (DataGroup.Item item : items) {
            Map<String, Object> values = item.getValues();
            List<Object> result = new ArrayList<>(values.size());
            for (ColumnHeader columnHeader : columnHeaders.getColumnHeaders()) {
                if (columnHeader.getIgnoreHeader()) {
                    continue;
                }
                if ((columnHeader.getGroupIndex() >= 0) && !columnHeader.getGroupIndex().equals(groupIndex)) {
                    continue;
                }

                String fieldName = columnHeader.getFieldName();
                Object value = values.get(fieldName);
                if (columnHeader.getDynamicColumn()) {
                    Map map = (Map) value;
                    Object o = map.get(columnHeader.getDynamicColumnKey());
                    result.add(o);
                } else {
                    result.add(value);
                }
            }
            data.add(result);
        }
        return data;
    }
}
