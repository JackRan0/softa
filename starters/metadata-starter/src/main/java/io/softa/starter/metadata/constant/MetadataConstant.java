package io.softa.starter.metadata.constant;

import java.util.Map;
import com.google.common.collect.ImmutableMap;

public interface MetadataConstant {

    /** Servlet URL pattern (filter form) for the signed prefix. */
    String SIGNED_URL_PATTERN = "/upgrade/runtime/*";

    String METADATA_EXPORT_API = "/upgrade/runtime/exportRuntimeMetadata";
    String METADATA_CHECKSUMS_API = "/upgrade/runtime/exportRuntimeChecksums";
    String METADATA_APPLY_DESIRED_API = "/upgrade/runtime/applyDesiredAggregates";

    /**
     * Maps each design-time meta-model name to its runtime ({@code Sys*}) counterpart. Insertion
     * order is parent→child, so iterating the key set gives a FK-safe apply order (and its reverse a
     * FK-safe delete order). Used by the drift-import apply path.
     */
    Map<String, String> DESIGN_TO_RUNTIME_MODELS = ImmutableMap.<String, String>builder()
            .put("DesignModel", "SysModel")
            .put("DesignModelTrans", "SysModelTrans")
            .put("DesignField", "SysField")
            .put("DesignFieldTrans", "SysFieldTrans")
            .put("DesignModelIndex", "SysModelIndex")
            .put("DesignOptionSet", "SysOptionSet")
            .put("DesignOptionSetTrans", "SysOptionSetTrans")
            .put("DesignOptionItem", "SysOptionItem")
            .put("DesignOptionItemTrans", "SysOptionItemTrans")
            .put("DesignView",  "SysView")
            .put("DesignNavigation",  "SysNavigation")
            .build();
}
