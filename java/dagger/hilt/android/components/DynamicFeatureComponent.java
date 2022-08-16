package dagger.hilt.android.components;

import dagger.hilt.DefineComponent;
import dagger.hilt.android.scopes.DynamicScoped;
import dagger.hilt.components.SingletonComponent;

@DynamicScoped
@DefineComponent(parent = SingletonComponent.class)
public interface DynamicFeatureComponent {}