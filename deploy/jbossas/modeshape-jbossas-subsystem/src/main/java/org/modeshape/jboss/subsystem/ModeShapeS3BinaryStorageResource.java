/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jboss.subsystem;

import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.registry.ManagementResourceRegistration;

/**
 * {@link SimpleResourceDefinition} which handles <s3-binary-storage/> elements.
 */
public class ModeShapeS3BinaryStorageResource extends SimpleResourceDefinition {
    protected final static ModeShapeS3BinaryStorageResource DEFAULT =
        new ModeShapeS3BinaryStorageResource(PathElement.pathElement(ModelKeys.STORAGE_TYPE,
                                                                     ModelKeys.S3_BINARY_STORAGE));

    private ModeShapeS3BinaryStorageResource(PathElement pathElement) {
        super(pathElement,
              ModeShapeExtension.getResourceDescriptionResolver(ModelKeys.REPOSITORY,
                                                                ModelKeys.S3_BINARY_STORAGE),
                                                                AddS3BinaryStorage.INSTANCE,
                                                                RemoveBinaryStorage.INSTANCE);
    }

    @Override
    public void registerAttributes( ManagementResourceRegistration resourceRegistration ) {
        BinaryStorageWriteAttributeHandler.S3_BINARY_STORAGE_INSTANCE.registerAttributes(resourceRegistration);
    }
}
