/**
 * Copyright 2013 Dennis Ippel
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.rajawali3d.materials.shaders;

import android.opengl.GLES20;
import org.rajawali3d.util.RajLog;
import org.rajawali3d.util.RawShaderLoader;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import c.org.rajawali3d.materials.shaders.ShaderBase;


/**
 * This class is a wrapper class for shader creation. GLSL shaders are text files that are
 * compiled at runtime. The GLSL language is based on C. This class is like a mini framework
 * that lets you write shaders in Java. The main reason for this is maintainability and code
 * reuse. The lack of operator overloading makes this slightly verbose however. Instead of
 * writing this in GLSL:
 *
 * <pre><code>
 * myVar *= myOtherVar;
 * </code></pre>
 *
 * You'll have to write this:
 *
 * <pre><code>
 * myVar.assignAdd(myOtherVar);
 * </code></pre>
 *
 * GLSL data types are wrapped into their own classes. Because most of the data type names are
 * reserved keywords in Java they are prefixed with 'R'.<br>
 * For instance:
 *
 * </p>
 * <ul>
 * 	<li>float: {@link RFloat}</li>
 * 	<li>vec2: {@link RVec2}</li>
 * 	<li>vec4: {@link RVec4}</li>
 * 	<li>mat3: {@link RMat3}</li>
 * 	<li>sampler2D: {@link RSampler2D}</li>
 * </ul>
 *
 * Shader initialization should be done in the {@link Shader#initialize()} method. This is
 * the place where you would create your uniforms, varyings, constanst, etc:
 *
 * <pre><code>
 * @Override
 * public void initialize()
 * {
 * 		super.initialize();
 * 		muMyVec3Uniform 		= (RVec3) addUniform("uMyVec3Uniform", DataType.VEC3);
 * 		maMyVec2Attribute	= (RVec2) addAttribute("uMyVec2Attribute", DataType.VEC2);
 * 		mvMyFloatVarying	 	= (RFloat) addVarying("vMyFloatVarying", DataType.FLOAT);
 * 		mgMyMat4Global 		= (RMat4) addGlobal("gMyMat4Global", DataType.MAT4);
 * 		mcMyIntConstant 		= (RInt) addConstant("cMyIntConstant", DataType.INT);
 * }
 * </code></pre>
 *
 * All attributes and uniforms needs to get their handles. This is an integer that represents
 * the location of a specific attribute or uniform within a shader program.
 *
 * <pre><code>
 * @Override
 * public void setLocations(int programHandle) {
 * 		muMyVec3UniformHandle 	= getUniformLocation(programHandle, "uMyVec3Uniform");
 * 		maMyVec2AttributeHandle	= getAttributeLocation(programHandle, "uMyVec2Attribute");
 * }
 * </code></pre>
 *
 * This handle is subsequently used in {@link Shader#applyParams())} to set the attribute/uniform value:
 *
 * <pre><code>
 * @Override
 * public void applyParams() {
 * 		super.applyParams();
 * 		GLES20.glUniform3fv(muMyVec3UniformHandle, 1, myFloatArrayValue, 0);
 * }
 * </code></pre>
 *
 * The shader code that goes into main() in a regular shader goes into {@link Shader#main()}:
 *
 * <pre><code>
 * @Override
 * public void main() {
 *	// corresponds to GLSL: 	vec3 myVar = maMyVec3Uniform;
 * 	RVec3 myVar = new RVec3("myVar");
 *	myVar.assign(maMyVec3Uniform);
 *	// corresponds to GLSL:		myVar *= 1.0f;
 * 	myVar.assignMultiply(1.0f);
 * 	// etc ..
 * }
 * </code></pre>
 *
 * @author dennis.ippel
 *
 */
public abstract class Shader extends ShaderBase {
	public static String SHADER_ID;

	public static enum ShaderType {
		VERTEX, FRAGMENT, VERTEX_SHADER_FRAGMENT, FRAGMENT_SHADER_FRAGMENT
	}

	public static enum Operator {
		LESS_THAN("<"), LESS_THAN_EQUALS("<="), GREATER_THAN(">"), GREATER_THAN_EQUALS(">="),
		EQUALS("=="), NOT_EQUALS("!="), AND("&&"), OR("||"), XOR("^^");

		private String mOperatorString;

		Operator(String operatorString) {
			mOperatorString = operatorString;
		}

		public String getOperatorString() {
			return mOperatorString;
		}
	}

	/**
	 * Defines the position of the current vertex. This is used in the vertex shader to
	 * write the final vertex position to. This corresponds to the gl_Position GLSL variable.
	 */
	protected final GLPosition GL_POSITION = new GLPosition();
	/**
	 * Defines the color of the current fragment. This is used in the fragment shader to
	 * write the final fragment color to. This corresponds to the gl_FragColor GLSL variable.
	 */
	protected final GLFragColor GL_FRAG_COLOR = new GLFragColor();
	/**
	 * Contains the window-relative coordinates of the current fragment
	 */
	protected final GLFragCoord GL_FRAG_COORD = new GLFragCoord();
	/**
	 * Specifies depth range in window coordinates. If an implementation does
	 * not support highp precision in the fragment language, and state is listed as
	 * highp, then that state will only be available as mediump in the fragment
	 * language.
	 */
	protected final GLDepthRange GL_DEPTH_RANGE = new GLDepthRange();

	protected String mShaderString;

	private ShaderType mShaderType;
	private List<String> mPreprocessorDirectives;
	private Hashtable<String, ShaderVar> mUniforms;
	private Hashtable<String, ShaderVar> mAttributes;
	private Hashtable<String, ShaderVar> mVaryings;
	private Hashtable<String, ShaderVar> mGlobals;
	private Hashtable<String, Precision> mPrecisionQualifier;
	private Hashtable<String, ShaderVar> mConstants;
	protected List<IShaderFragment> mShaderFragments;
	protected int mProgramHandle;
	protected boolean mNeedsBuild = true;

	public Shader() {}

	public Shader(ShaderType shaderType) {
		mShaderType = shaderType;
	}

	public Shader(ShaderType shaderType, int resourceId) {
		this(shaderType, RawShaderLoader.fetch(resourceId));
	}

	public Shader(ShaderType shaderType, String shaderString) {
		mShaderType = shaderType;
		mShaderString = shaderString;
	}

	public void initialize() {
		mUniforms = new Hashtable<String, ShaderVar>();
		mAttributes = new Hashtable<String, ShaderVar>();
		mVaryings = new Hashtable<String, ShaderVar>();
		mGlobals = new Hashtable<String, ShaderVar>();
		mPrecisionQualifier = new Hashtable<String, Precision>();
		mConstants = new Hashtable<String, ShaderVar>();
		mShaderFragments = new ArrayList<IShaderFragment>();
	}

	public void main() {
	}

	/**
	 * Add a preprocessor directive like #define, #extension, #version etc.
	 *
	 * @param directive
	 */
	public void addPreprocessorDirective(String directive)
	{
		if(mPreprocessorDirectives == null)
			mPreprocessorDirectives = new ArrayList<String>();
		mPreprocessorDirectives.add(directive);
	}

	/**
	 * Add a precision qualifier. There are three precision qualifiers: highp​, mediump​, and lowp​.
	 * They have no semantic meaning or functional effect. They can apply to any floating-point type
	 * (vector or matrix), or any integer type. All variables of a certain type can be declared to
	 * have a precision by using the precision​ statement. It's syntax is as follows:
	 * <p>precision precision-qualifier​ type​;</p>
	 * In this case, type​ can only be float​ or int​. This will affect all collections of that basic type.
	 * So float​ will affect all floating-point scalars, vectors, and matrices. The int​ type will affect
	 * all signed and unsigned integer scalars and vectors.
	 *
	 * @param dataType float, int
	 * @param precision highp, mediump, lowp
	 */
	protected void addPrecisionQualifier(DataType dataType, Precision precision) {
		mPrecisionQualifier.put(dataType.getTypeString(), precision);
	}

	/**
	 * Add a uniform. The uniform qualifier is used to declare global variables whose values are the same across the entire
	 * primitive being processed. All uniform variables are read-only and are initialized externally either at link
	 * time or through the API. The link time initial value is either the value of the variable's initializer, if
	 * present, or 0 if no initializer is present. Sampler types cannot have initializers.
	 *
	 * @param var	A global shader variable.
	 * @return
	 */
	protected ShaderVar addUniform(GlobalShaderVar var)
	{
		return addUniform(var.getName(), var.getType());
	}

	/**
	 * Add a uniform. The uniform qualifier is used to declare global variables whose values are the same across the entire
	 * primitive being processed. All uniform variables are read-only and are initialized externally either at link
	 * time or through the API. The link time initial value is either the value of the variable's initializer, if
	 * present, or 0 if no initializer is present. Sampler types cannot have initializers.
	 *
	 * @param var	A global shader variable
	 * @param index	The index for the shader variable. This number will appear suffixed in the final shader string.
	 * @return
	 */
	protected ShaderVar addUniform(GlobalShaderVar var, int index)
	{
		return addUniform(var.getName() + Integer.toString(index), var.getType());
	}

	/**
	 * Add a uniform. The uniform qualifier is used to declare global variables whose values are the same across the entire
	 * primitive being processed. All uniform variables are read-only and are initialized externally either at link
	 * time or through the API. The link time initial value is either the value of the variable's initializer, if
	 * present, or 0 if no initializer is present. Sampler types cannot have initializers.
	 *
	 * @param var		A global shader variable
	 * @param suffix	A string that will appear suffixed in the final shader string.
	 * @return
	 */
	protected ShaderVar addUniform(GlobalShaderVar var, String suffix)
	{
		return addUniform(var.getName() + suffix, var.getType());
	}

	/**
	 * Add a uniform. The uniform qualifier is used to declare global variables whose values are the same across the entire
	 * primitive being processed. All uniform variables are read-only and are initialized externally either at link
	 * time or through the API. The link time initial value is either the value of the variable's initializer, if
	 * present, or 0 if no initializer is present. Sampler types cannot have initializers.
	 *
	 * @param name		The uniform name
	 * @param dataType	The uniform data type
	 * @return
	 */
	protected ShaderVar addUniform(String name, DataType dataType)
	{
		ShaderVar v = getInstanceForDataType(name, dataType);
		v.isGlobal(true);
		mUniforms.put(v.getName(), v);
		return v;
	}

	public void setUniform1f(String name, float value)
	{
		int handle = getUniformLocation(mProgramHandle, name);
		GLES20.glUniform1f(handle, value);
	}

	public void setUniform2fv(String name, float[] value)
	{
		int handle = getUniformLocation(mProgramHandle, name);
		GLES20.glUniform2fv(handle, 1, value, 0);
	}

	public void setUniform3fv(String name, float[] value)
	{
		int handle = getUniformLocation(mProgramHandle, name);
		GLES20.glUniform3fv(handle, 1, value, 0);
	}

	public void setUniform1i(String name, int value)
	{
		int handle = getUniformLocation(mProgramHandle, name);
		GLES20.glUniform1i(handle, value);
	}

	/**
	 * Returns all preprocessor directives.
	 *
	 * @return
     */
	public List<String> getPreprocessorDirectives() {
		return mPreprocessorDirectives;
	}

	/**
	 * Returns all uniforms
	 *
	 * @return
	 */
	public Hashtable<String, ShaderVar> getUniforms()
	{
		return mUniforms;
	}

	/**
	 * The attribute qualifier is used to declare variables that are passed to a vertex shader from OpenGL on a
	 * per-vertex basis. It is an error to declare an attribute variable in any type of shader other than a vertex
	 * shader. Attribute variables are read-only as far as the vertex shader is concerned. Values for attribute
	 * variables are passed to a vertex shader through the OpenGL vertex API or as part of a vertex array. They
	 * convey vertex attributes to the vertex shader and are expected to change on every vertex shader run. The
	 * attribute qualifier can be used only with float, floating-point vectors, and matrices. Attribute variables
	 * cannot be declared as arrays or structures.
	 *
	 * @param var	A global shader variable
	 * @return
	 */
	protected ShaderVar addAttribute(GlobalShaderVar var)
	{
		return addAttribute(var.getName(), var.getType());
	}

	/**
	 * The attribute qualifier is used to declare variables that are passed to a vertex shader from OpenGL on a
	 * per-vertex basis. It is an error to declare an attribute variable in any type of shader other than a vertex
	 * shader. Attribute variables are read-only as far as the vertex shader is concerned. Values for attribute
	 * variables are passed to a vertex shader through the OpenGL vertex API or as part of a vertex array. They
	 * convey vertex attributes to the vertex shader and are expected to change on every vertex shader run. The
	 * attribute qualifier can be used only with float, floating-point vectors, and matrices. Attribute variables
	 * cannot be declared as arrays or structures.
	 *
	 * @param name		The attribute name
	 * @param dataType	The attribute data type
	 * @return
	 */
	protected ShaderVar addAttribute(String name, DataType dataType) {
		ShaderVar v = getInstanceForDataType(name, dataType);
		v.isGlobal(true);
		mAttributes.put(v.getName(), v);
		return v;
	}

	/**
	 * Returns all attributes
	 * @return
	 */
	public Hashtable<String, ShaderVar> getAttributes()
	{
		return mAttributes;
	}

	/**
	 * Varying variables provide the interface between the vertex shaders, the fragment shaders, and the fixed
	 * functionality between them. Vertex shaders will compute values per vertex (such as color, texture
	 * coordinates, etc.) and write them to variables declared with the varying qualifier. A vertex shader may
	 * also read varying variables, getting back the same values it has written. Reading a varying variable in a
	 * vertex shader returns undefined values if it is read before being written.
	 *
	 * @param var
	 * @return
	 */
	protected ShaderVar addVarying(GlobalShaderVar var) {
		return addVarying(var.getName(), var.getType());
	}

	/**
	 * Varying variables provide the interface between the vertex shaders, the fragment shaders, and the fixed
	 * functionality between them. Vertex shaders will compute values per vertex (such as color, texture
	 * coordinates, etc.) and write them to variables declared with the varying qualifier. A vertex shader may
	 * also read varying variables, getting back the same values it has written. Reading a varying variable in a
	 * vertex shader returns undefined values if it is read before being written.
	 *
	 * @param var	A global shader variable
	 * @param index	The index for the shader variable. This number will appear suffixed in the final shader string.
	 * @return
	 */
	protected ShaderVar addVarying(GlobalShaderVar var, int index)
	{
		return addVarying(var.getName() + Integer.toString(index), var.getType());
	}

	/**
	 * Varying variables provide the interface between the vertex shaders, the fragment shaders, and the fixed
	 * functionality between them. Vertex shaders will compute values per vertex (such as color, texture
	 * coordinates, etc.) and write them to variables declared with the varying qualifier. A vertex shader may
	 * also read varying variables, getting back the same values it has written. Reading a varying variable in a
	 * vertex shader returns undefined values if it is read before being written.
	 *
	 * @param name		The varying name
	 * @param dataType	The varying data type
	 * @return
	 */
	protected ShaderVar addVarying(String name, DataType dataType) {
		ShaderVar v = getInstanceForDataType(name, dataType);
		v.isGlobal(true);
		mVaryings.put(v.getName(), v);
		return v;
	}

	/**
	 * Returns all varyings
	 *
	 * @return
	 */
	public Hashtable<String, ShaderVar> getVaryings()
	{
		return mVaryings;
	}

	/**
	 * Adds a global variable
	 *
	 * @param var	A global shader variable
	 * @return
	 */
	protected ShaderVar addGlobal(GlobalShaderVar var) {
		return addGlobal(var.getName(), var.getType());
	}

	/**
	 * Adds a global variable
	 *
	 * @param var	A global shader variable
	 * @param index	The index for the shader variable. This number will appear suffixed in the final shader string.
	 * @return
	 */
	protected ShaderVar addGlobal(GlobalShaderVar var, int index) {
		return addGlobal(var.getName() + Integer.toString(index), var.getType());
	}

	/**
	 * Adds a global variable
	 *
	 * @param name		The global name
	 * @param dataType	The global data type
	 * @return
	 */
	protected ShaderVar addGlobal(String name, DataType dataType) {
		ShaderVar v = getInstanceForDataType(name, dataType);
		v.isGlobal(true);
		mGlobals.put(v.getName(), v);
		return v;
	}

	/**
	 * Returns all globals
	 *
	 * @return
	 */
	public Hashtable<String, ShaderVar> getGlobals()
	{
		return mGlobals;
	}

	/**
	 * Returns a global. This can be a regular global but also a uniform, attribute, const or varying.
	 *
	 * @param var
	 * @return
	 */
	public ShaderVar getGlobal(GlobalShaderVar var)
	{
		ShaderVar v = getInstanceForDataType(var.getName(), var.getType());
		v.initialized = true;
		return v;
	}

	/**
	 * Returns a global. This can be a regular global but also a uniform, attribute, const or varying.
	 *
	 * @param var
	 * @param index
	 * @return
	 */
	public ShaderVar getGlobal(GlobalShaderVar var, int index)
	{
		ShaderVar v = getInstanceForDataType(var.getName() + Integer.toString(index), var.getType());
		v.initialized = true;
		return v;
	}

	/**
	 * Add a constant
	 *
	 * @param name
	 * @param value
	 * @return
	 */
	protected ShaderVar addConst(String name, int value) {
		return addConst(name, new RInt(value));
	}

	/**
	 * Add a constant
	 *
	 * @param name
	 * @param value
	 * @return
	 */
	protected ShaderVar addConst(String name, float value) {
		return addConst(name, new RFloat(value));
	}

	/**
	 * Add a constant
	 *
	 * @param name
	 * @param value
	 * @return
	 */
	protected ShaderVar addConst(String name, double value) {
		return addConst(name, (float)value);
	}

	/**
	 * Add a constant
	 *
	 * @param name
	 * @param var
	 * @return
	 */
	protected ShaderVar addConst(String name, ShaderVar var) {
		ShaderVar v = getInstanceForDataType(name, var.getDataType());
		v.setValue(var.getValue());
		v.isGlobal(true);
		mConstants.put(v.getName(), v);
		return v;
	}

	/**
	 * Returns all constants
	 *
	 * @return
	 */
	public Hashtable<String, ShaderVar> getConsts()
	{
		return mConstants;
	}

	public void setLocations(final int programHandle)
	{
		mProgramHandle = programHandle;
		if(mShaderFragments != null)
			for(int i=0; i<mShaderFragments.size(); i++)
				mShaderFragments.get(i).setLocations(programHandle);
	}

	protected int getUniformLocation(int programHandle, GlobalShaderVar var) {
		return getUniformLocation(programHandle, var.getName());
	}

	protected int getUniformLocation(int programHandle, GlobalShaderVar var, int index) {
		return getUniformLocation(programHandle, var.getName() + Integer.toString(index));
	}

	protected int getUniformLocation(int programHandle, GlobalShaderVar var, String suffix) {
		return getUniformLocation(programHandle, var.getName() + suffix);
	}

	protected int getUniformLocation(int programHandle, String name) {
		int result = GLES20.glGetUniformLocation(programHandle, name);
        if (result < 0 && RajLog.isDebugEnabled()) RajLog.e("Getting location of uniform: " + name + " returned -1!");
		return result;
	}

	protected int getAttribLocation(int programHandle, GlobalShaderVar var) {
		return getAttribLocation(programHandle, var.getName());
	}

	protected int getAttribLocation(int programHandle, GlobalShaderVar var, int index) {
		return getAttribLocation(programHandle, var.getName() + Integer.toString(index));
	}

	protected int getAttribLocation(int programHandle, String name) {
		int result = GLES20.glGetAttribLocation(programHandle, name);
		return result;
	}

	public void addShaderFragment(IShaderFragment fragment)
	{
		if(fragment == null) return;
		mShaderFragments.add(fragment);
	}

	public IShaderFragment getShaderFragment(String shaderId) {
		for(IShaderFragment frag : mShaderFragments)
			if(frag.getShaderId().equals(shaderId))
				return frag;

		return null;
	}

	public ShaderType getShaderType() {
		return mShaderType;
	}

	public void setStringBuilder(StringBuilder stringBuilder)
	{
		shaderSB = stringBuilder;
	}

	public String getShaderString() {
		return mShaderString;
	}

	public void buildShader() {
		shaderSB = new StringBuilder();
		StringBuilder s = shaderSB;

		//
		// -- Preprocessor directives
		//
		if(mPreprocessorDirectives != null)
		{
			for(String directive : mPreprocessorDirectives)
			{
				s.append(directive).append("\n");
			}
		}
        for (IShaderFragment frag : mShaderFragments) {
            if (frag instanceof Shader) {
                final List<String> preprocessorDirectives = frag.getPreprocessorDirectives();
                if (preprocessorDirectives != null) {
                    for (String directive : preprocessorDirectives) {
                        s.append(directive).append("\n");
                    }
                }
            }
        }

		//
		// -- Precision statements
		//

		Set<Entry<String, Precision>> precisionSet = mPrecisionQualifier
				.entrySet();
		Iterator<Entry<String, Precision>> precisionIter = precisionSet
				.iterator();

		while (precisionIter.hasNext()) {
			Entry<String, Precision> e = precisionIter.next();
			s.append("precision ").append(e.getValue().getPrecisionString())
					.append(" ").append(e.getKey()).append(";\n");
		}

		//
		// -- Constants
		//

		Hashtable<String, ShaderVar> consts = new Hashtable<String, ShaderVar>(mConstants);

		for(int i=0; i<mShaderFragments.size(); i++)
		{
			IShaderFragment fragment = mShaderFragments.get(i);
			if(fragment.getConsts() != null)
				consts.putAll(fragment.getConsts());
		}

		Set<Entry<String, ShaderVar>> set = consts.entrySet();
		Iterator<Entry<String, ShaderVar>> iter = set.iterator();
		while (iter.hasNext()) {
			Entry<String, ShaderVar> e = iter.next();
			ShaderVar var = e.getValue();

			String arrayStr = var.isArray() ? "[" +var.getArraySize()+ "]" : "";

			s.append("const ").append(var.dataType.getTypeString())
					.append(" ").append(var.name).append(arrayStr)
					.append(" = ").append(var.getValue()).append(";\n");
		}

		//
		// -- Uniforms
		//

		Hashtable<String, ShaderVar> uniforms = new Hashtable<String, ShaderVar>(mUniforms);

		for(int i=0; i<mShaderFragments.size(); i++)
		{
			IShaderFragment fragment = mShaderFragments.get(i);
			if(fragment.getUniforms() != null)
				uniforms.putAll(fragment.getUniforms());
		}

		set = uniforms.entrySet();
		iter = set.iterator();
		while (iter.hasNext()) {
			Entry<String, ShaderVar> e = iter.next();
			ShaderVar var = e.getValue();

			String arrayStr = var.isArray() ? "[" +var.getArraySize()+ "]" : "";

			s.append("uniform ").append(var.dataType.getTypeString())
					.append(" ").append(var.name).append(arrayStr).append(";\n");
		}

		//
		// -- Attributes
		//

		Hashtable<String, ShaderVar> attributes = new Hashtable<String, ShaderVar>(mAttributes);

		for(int i=0; i<mShaderFragments.size(); i++)
		{
			IShaderFragment fragment = mShaderFragments.get(i);
			if(fragment.getAttributes() != null)
				attributes.putAll(fragment.getAttributes());
		}

		set = attributes.entrySet();
		iter = set.iterator();

		while (iter.hasNext()) {
			Entry<String, ShaderVar> e = iter.next();
			ShaderVar var = e.getValue();
			s.append("attribute ").append(var.dataType.getTypeString())
					.append(" ").append(var.name).append(";\n");
		}

		//
		// -- Varying
		//

		Hashtable<String, ShaderVar> varyings = new Hashtable<String, ShaderVar>(mVaryings);

		for(int i=0; i<mShaderFragments.size(); i++)
		{
			IShaderFragment fragment = mShaderFragments.get(i);
			if(fragment.getVaryings() != null)
				varyings.putAll(fragment.getVaryings());
		}

		set = varyings.entrySet();
		iter = set.iterator();

		while (iter.hasNext()) {
			Entry<String, ShaderVar> e = iter.next();
			ShaderVar var = e.getValue();
			String arrayStr = var.isArray() ? "[" +var.getArraySize()+ "]" : "";
			s.append("varying ").append(var.dataType.getTypeString())
					.append(" ").append(var.name).append(arrayStr).append(";\n");
		}

		//
		// -- Global
		//

		Hashtable<String, ShaderVar> globals = new Hashtable<String, ShaderVar>(mGlobals);

		for(int i=0; i<mShaderFragments.size(); i++)
		{
			IShaderFragment fragment = mShaderFragments.get(i);
			if(fragment.getGlobals() != null)
				globals.putAll(fragment.getGlobals());
		}

		set = globals.entrySet();
		iter = set.iterator();

		while (iter.hasNext()) {
			Entry<String, ShaderVar> e = iter.next();
			ShaderVar var = e.getValue();
			String arrayStr = var.isArray() ? "[" +var.getArraySize()+ "]" : "";
			s.append(var.dataType.getTypeString())
					.append(" ").append(var.name).append(arrayStr).append(";\n");
		}

		//
		// -- Call main
		//

		s.append("\nvoid main() {\n");
		main();
		s.append("}\n");

		mShaderString = s.toString();
		s = null;
	}

	/**
	 * applyParams() should be called on every frame. The shader parameters
	 * are set here.
	 */
	public void applyParams()
	{
		if(mShaderFragments != null)
			for(int i=0; i<mShaderFragments.size(); i++)
				mShaderFragments.get(i).applyParams();
	}

	public int getProgramHandle()
	{
		return mProgramHandle;
	}

	public ShaderVar subtract(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName(var1.getName() + " - " + var2.getName());
		var.initialized = true;
		return var;
	}

	public ShaderVar subtract(float value1, ShaderVar var2)
	{
		return subtract(new RFloat(Float.toString(value1)), var2);
	}

	public ShaderVar divide(Float value1, ShaderVar var2)
	{
		return divide(new RFloat(Float.toString(value1)), var2);
	}

	public ShaderVar divide(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName(var1.getName() + " / " + var2.getName());
		var.initialized = true;
		return var;
	}

	public ShaderVar multiply(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName(var1.getName() + " * " + var2.getName());
		var.initialized = true;
		return var;
	}

	public ShaderVar max(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName("max(" + var1.getName() + ", " + var2.getName() + ")");
		var.initialized = true;
		return var;
	}

	public ShaderVar max(ShaderVar var1, float value2)
	{
		ShaderVar s = new ShaderVar("max(" + var1.getName() + ", " + Float.toString(value2) + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar min(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName("min(" + var1.getName() + ", " + var2.getName() + ")");
		var.initialized = true;
		return var;
	}

	public ShaderVar min(ShaderVar var1, float value2)
	{
		ShaderVar s = new ShaderVar("min(" + var1.getName() + ", " + Float.toString(value2) + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public String normalize(String value)
	{
		return "normalize(" + value + ")";
	}

	public String normalize(ShaderVar value)
	{
		return normalize(value.getName());
	}

	public ShaderVar sqrt(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("sqrt(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar inversesqrt(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("inversesqrt(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar texture1D(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("texture1D(" + var1.getName() + ", " + var2.getName() + ")", DataType.VEC4);
		s.initialized = true;
		return s;
	}

	public ShaderVar texture2D(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("texture2D(" + var1.getName() + ", " + var2.getName() + ")", DataType.VEC4);
		s.initialized = true;
		return s;
	}

	public ShaderVar texture3D(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("texture3D(" + var1.getName() + ", " + var2.getName() + ")", DataType.VEC4);
		s.initialized = true;
		return s;
	}

	public ShaderVar textureCube(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("textureCube(" + var1.getName() + ", " + var2.getName() + ")", DataType.VEC4);
		s.initialized = true;
		return s;
	}

	public RVec4 texture2DProj(ShaderVar var1, ShaderVar var2)
	{
		RVec4 s = new RVec4("texture2DProj(" + var1.getName() + ", " + var2.getName() + ")", DataType.VEC4);
		s.initialized = true;
		return s;
	}

	public ShaderVar distance(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("distance(" + var1.getName() + ", " + var2.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar clamp(ShaderVar var, float value1, float value2)
	{
		ShaderVar s = new ShaderVar("clamp(" + var.getName() + ", " + Float.toString(value1) + ", " + Float.toString(value2) + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar mix(ShaderVar var1, ShaderVar var2, float value)
	{
		ShaderVar s = new ShaderVar("mix(" + var1.getName() + ", " + var2.getName() + ", " + Float.toString(value) + ")", DataType.VEC3);
		s.initialized = true;
		return s;
	}

	public ShaderVar mix(ShaderVar var1, ShaderVar var2, ShaderVar var3)
	{
		ShaderVar s = new ShaderVar("mix(" + var1.getName() + ", " + var2.getName() + ", " + var3.getName() + ")", DataType.VEC3);
		s.initialized = true;
		return s;
	}

	public ShaderVar dot(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("dot(" + var1.getName() + ", " + var2.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar cos(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("cos(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar acos(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("acos(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar sin(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("sin(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar tan(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("tan(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

    public ShaderVar atan(ShaderVar y, ShaderVar x) {
        ShaderVar s = new ShaderVar("atan(" + y.getName() + ", " + x.getName() + ")", DataType.FLOAT);
        s.initialized = true;
        return s;
    }


    public ShaderVar pow(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("pow(" + var1.getName() + ", " + var2.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar mod(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar s = new ShaderVar("mod(" + var1.getName() + ", " + var2.getName() + ")", var1.getDataType());
		s.initialized = true;
		return s;
	}

	public ShaderVar length(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("length(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar floor(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("floor(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar radians(ShaderVar var)
	{
		ShaderVar s = new ShaderVar("radians(" + var.getName() + ")", DataType.FLOAT);
		s.initialized = true;
		return s;
	}

	public ShaderVar reflect(ShaderVar var1, ShaderVar var2)
	{
		ShaderVar var = getInstanceForDataType(var1.getDataType());
		var.setName("reflect(" + var1.getName() + ", " + var2.getName() + ")");
		var.initialized = true;
		return var;
	}

	public void discard()
	{
		shaderSB.append("discard;\n");
	}

	public static class Condition {
		private ShaderVar mLeftValue;
		private Operator mOperator;
		private String mRightValue;
		private Operator mJoinOperator;

		public Condition(Operator joinOperator, ShaderVar leftValue, Operator operator, String rightValue) {
			mJoinOperator = joinOperator;
			mLeftValue = leftValue;
			mOperator = operator;
			mRightValue = rightValue;
		}

		public Condition(Operator joinOperator, ShaderVar leftValue, Operator operator, ShaderVar rightValue) {
			this(joinOperator, leftValue, operator, rightValue.getName());
		}

		public Condition(Operator joinOperator, ShaderVar leftValue, Operator operator, float rightValue) {
			this(joinOperator, leftValue, operator, Float.toString(rightValue));
		}

		public Condition(Operator joinOperator, ShaderVar leftValue, Operator operator, boolean rightValue)
		{
			this(joinOperator, leftValue, operator, rightValue == true ? "true" : "false");
		}

		public Condition(ShaderVar leftValue, Operator operator, String rightValue) {
			this(null, leftValue, operator, rightValue);
		}

		public Condition(ShaderVar leftValue, Operator operator, ShaderVar rightValue) {
			this(leftValue, operator, rightValue.getName());
		}

		public Condition(ShaderVar leftValue, Operator operator, float rightValue)
		{
			this(leftValue, operator, Float.toString(rightValue));
		}

		public Condition(ShaderVar leftValue, Operator operator, boolean rightValue)
		{
			this(leftValue, operator, rightValue == true ? "true" : "false");
		}

		public ShaderVar getLeftValue() {
			return mLeftValue;
		}

		public Operator getOperator() {
			return mOperator;
		}

		public String getRightValue() {
			return mRightValue;
		}

		public Operator getJoinOperator() {
			return mJoinOperator;
		}
	}

	public void startif(Condition... conditions) {
		shaderSB.append("if(");
		for(int i=0; i<conditions.length; i++) {
			Condition condition = conditions[i];
			if(i > 0) shaderSB.append(condition.getJoinOperator().getOperatorString());
			shaderSB.append(condition.getLeftValue().getVarName());
			shaderSB.append(condition.getOperator().getOperatorString());
			shaderSB.append(condition.getRightValue());
		}
		shaderSB.append(")\n{\n");
	}

	public void startif(Condition condition)
	{
		shaderSB.append("if(");
		shaderSB.append(condition.getLeftValue().getVarName());
		shaderSB.append(condition.getOperator().getOperatorString());
		shaderSB.append(condition.getRightValue());
		shaderSB.append(")\n{\n");
	}

	public void ifelseif(Condition... conditions)
	{
		shaderSB.append("} else ");
		shaderSB.append("if(");
		for(int i=0; i<conditions.length; i++) {
			Condition condition = conditions[i];
			if(i > 0) shaderSB.append(condition.getJoinOperator().getOperatorString());
			shaderSB.append(condition.getLeftValue().getVarName());
			shaderSB.append(condition.getOperator().getOperatorString());
			shaderSB.append(condition.getRightValue());
		}
		shaderSB.append(")\n{\n");
	}

	public void ifelseif(Condition condition)
	{
		shaderSB.append("} else ");
		shaderSB.append("if(");
		shaderSB.append(condition.getLeftValue().getVarName());
		shaderSB.append(condition.getOperator().getOperatorString());
		shaderSB.append(condition.getRightValue());
		shaderSB.append(")\n{\n");
	}

	public void ifelse()
	{
		shaderSB.append("} else {\n");
	}

	public void endif()
	{
		shaderSB.append("}\n");
	}

	public ShaderVar castInt(float value)
	{
		return castInt(Float.toString(value));
	}

	public ShaderVar castInt(ShaderVar value)
	{
		return castInt(value.getVarName());
	}

	public ShaderVar castInt(String value)
	{
		ShaderVar v = new ShaderVar("int(" + value + ")", DataType.INT);
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec2(float x)
	{
		return castVec2(Float.toString(x));
	}

	public ShaderVar castVec2(float x, float y)
	{
		return castVec2(Float.toString(x), Float.toString(y));
	}

	public ShaderVar castVec2(String x, String y)
	{
		ShaderVar v = new ShaderVar("vec2(" + x + ", " + y + ")", DataType.VEC2);
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec2(ShaderVar x, ShaderVar y)
	{
		return castVec2(x.getVarName(), y.getVarName());
	}

	public ShaderVar castVec2(String x)
	{
		ShaderVar v = new ShaderVar("vec2(" + x + ")", DataType.VEC2);
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec2(ShaderVar x)
	{
		return castVec2(x.getVarName());
	}

	public ShaderVar castVec3(float x, float y, float z)
	{
		return castVec3(new RFloat(x), new RFloat(y), new RFloat(z));
	}

	public ShaderVar castVec3(ShaderVar x, ShaderVar y, ShaderVar z)
	{
		ShaderVar v = new ShaderVar(DataType.VEC3);
        v.setValue("vec3(" + x.getName() + ", " + y.getName() + ", " + z.getName() + ")");
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec3(String var)
	{
		ShaderVar v = new ShaderVar("vec3(" + var + ")", DataType.VEC3);
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec3(ShaderVar var)
	{
		return castVec3(var.getVarName());
	}

	public ShaderVar castVec4(float value)
	{
		return castVec4(Float.toString(value));
	}

	public ShaderVar castVec4(ShaderVar var)
	{
		return castVec4(var.getVarName());
	}

	public ShaderVar castVec4(String var)
	{
		ShaderVar v = new ShaderVar("vec4(" + var + ")", DataType.VEC4);
		v.initialized = true;
		return v;
	}

	public ShaderVar castVec4(ShaderVar var, float value)
	{
		return castVec4(var.getVarName(), value);
	}

	public ShaderVar castVec4(String var, float value)
	{
		ShaderVar v = new ShaderVar("vec4(" + var + ", " + value + ")", DataType.VEC4);
		v.initialized = true;
		return v;
	}

	public ShaderVar castMat3(float value)
	{
		return castMat3(new RFloat(value));
	}

	public ShaderVar castMat3(ShaderVar var)
	{
		ShaderVar v = new ShaderVar("mat3(" + var.getName() + ")", DataType.MAT3);
		v.initialized = true;
		return v;
	}

	public ShaderVar castMat4(float value)
	{
		return castMat4(new RFloat(Float.toString(value)));
	}

	public ShaderVar castMat4(ShaderVar var)
	{
		ShaderVar v = new ShaderVar("mat4(" + var.getName() + ")", DataType.MAT3);
		v.initialized = true;
		return v;
	}

	public ShaderVar enclose(ShaderVar value)
	{
		ShaderVar var = getReturnTypeForOperation(value.getDataType(), value.getDataType());
		var.setValue("(" + value.getName() + ")");
		var.setName(var.getValue());
		return var;
	}

	public boolean needsBuild() {
		return mNeedsBuild;
	}

	public void setNeedsBuild(boolean needsBuild) {
		mNeedsBuild = needsBuild;
	}
}