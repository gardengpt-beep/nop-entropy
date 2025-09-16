package com.company.erp;

import com.company.erp.runtime.ModelDrivenErpEngine;

/**
 * Entry point for the offline ERP demo. The application simply loads the
 * metadata-driven engine so that it can be exercised manually or through the
 * custom test harness.
 */
public class ErpSpringProjectApplication {

    public static void main(String[] args) {
        ModelDrivenErpEngine engine = new ModelDrivenErpEngine();
        System.out.println("Model-driven ERP initialized with entity: "
                + engine.describeProductEntity());
    }
}
