/*
 * <<
 *  Davinci
 *  ==
 *  Copyright (C) 2016 - 2018 EDP
 *  ==
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  >>
 *
 */

package edp.davinci.service.impl;

import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import edp.core.exception.NotFoundException;
import edp.core.exception.ServerException;
import edp.core.exception.UnAuthorizedExecption;
import edp.core.model.Paginate;
import edp.core.model.PaginateWithQueryColumns;
import edp.core.utils.CollectionUtils;
import edp.core.utils.MD5Util;
import edp.core.utils.RedisUtils;
import edp.core.utils.SqlUtils;
import edp.davinci.core.common.Constants;
import edp.davinci.core.enums.LogNameEnum;
import edp.davinci.core.enums.SqlVariableTypeEnum;
import edp.davinci.core.enums.UserPermissionEnum;
import edp.davinci.core.model.SqlEntity;
import edp.davinci.core.utils.SqlParseUtils;
import edp.davinci.dao.RelRoleViewMapper;
import edp.davinci.dao.SourceMapper;
import edp.davinci.dao.ViewMapper;
import edp.davinci.dao.WidgetMapper;
import edp.davinci.dto.projectDto.ProjectDetail;
import edp.davinci.dto.projectDto.ProjectPermission;
import edp.davinci.dto.sourceDto.SourceBaseInfo;
import edp.davinci.dto.sourceDto.SourceConfig;
import edp.davinci.dto.viewDto.*;
import edp.davinci.model.*;
import edp.davinci.service.ProjectService;
import edp.davinci.service.SourceService;
import edp.davinci.service.ViewService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.STGroupFile;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static edp.core.consts.Consts.comma;
import static edp.core.consts.Consts.minus;

@Slf4j
@Service("viewService")
public class ViewServiceImpl implements ViewService {

    private static final Logger optLogger = LoggerFactory.getLogger(LogNameEnum.BUSINESS_OPERATION.getName());

    @Autowired
    private ViewMapper viewMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private WidgetMapper widgetMapper;

    @Autowired
    private RelRoleViewMapper relRoleViewMapper;

    @Autowired
    private SqlUtils sqlUtils;

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private SourceService sourceService;

    @Value("${sql_template_delimiter:$}")
    private String sqlTempDelimiter;

    private static final String SQL_VARABLE_KEY = "name";

    @Override
    public synchronized boolean isExist(String name, Long id, Long projectId) {
        Long viewId = viewMapper.getByNameWithProjectId(name, projectId);
        if (null != id && null != viewId) {
            return !id.equals(viewId);
        }
        return null != viewId && viewId.longValue() > 0L;
    }

    /**
     * 获取View列表
     *
     * @param projectId
     * @param user
     * @return
     */
    @Override
    public List<ViewBaseInfo> getViews(Long projectId, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(projectId, user, false);
        } catch (NotFoundException e) {
            throw e;
        } catch (UnAuthorizedExecption e) {
            return null;
        }

        List<ViewBaseInfo> views = viewMapper.getViewBaseInfoByProject(projectId);

        if (null != views) {
            ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
            if (projectPermission.getViewPermission() == UserPermissionEnum.HIDDEN.getPermission()) {
                return null;
            }
        }

        return views;
    }

    @Override
    public ViewWithSourceBaseInfo getView(Long id, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {
        ViewWithSourceBaseInfo view = viewMapper.getViewWithSourceBaseInfo(id);
        if (null == view) {
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getViewPermission() == UserPermissionEnum.HIDDEN.getPermission()) {
            throw new NotFoundException("Insufficient permissions");
        }

        return view;
    }

    /**
     * 新建View
     *
     * @param viewCreate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public ViewWithSourceBaseInfo createView(ViewCreate viewCreate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {
        ProjectDetail projectDetail = projectService.getProjectDetail(viewCreate.getProjectId(), user, false);
        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);

        if (projectPermission.getViewPermission() < UserPermissionEnum.WRITE.getPermission()) {
            throw new UnAuthorizedExecption("you have not permission to create view");
        }

        if (isExist(viewCreate.getName(), null, viewCreate.getProjectId())) {
            log.info("the view {} name is already taken", viewCreate.getName());
            throw new ServerException("the view name is already taken");
        }

        Source source = sourceMapper.getById(viewCreate.getSourceId());
        if (null == source) {
            log.info("source (:{}) not found", viewCreate.getSourceId());
            throw new NotFoundException("source is not found");
        }

        //测试连接
        boolean testConnection = sourceService.isTestConnection(new SourceConfig(source));

        if (testConnection) {
            View view = new View().createdBy(user.getId());
            BeanUtils.copyProperties(viewCreate, view);

            int insert = viewMapper.insert(view);
            if (insert > 0) {
                optLogger.info("view ({}) is create by user (:{})", view.toString(), user.getId());
                if (null != viewCreate.getRoles() && viewCreate.getRoles().size() > 0 && !StringUtils.isEmpty(viewCreate.getVariable())) {
                    checkAndInsertRoleParam(viewCreate.getVariable(), viewCreate.getRoles(), user, view);
                }

                SourceBaseInfo sourceBaseInfo = new SourceBaseInfo();
                BeanUtils.copyProperties(source, sourceBaseInfo);

                ViewWithSourceBaseInfo viewWithSource = new ViewWithSourceBaseInfo();
                BeanUtils.copyProperties(view, viewWithSource);
                viewWithSource.setSource(sourceBaseInfo);
                return viewWithSource;
            } else {
                throw new ServerException("create view fail");
            }
        } else {
            throw new ServerException("get source connection fail");
        }
    }


    /**
     * 更新View
     *
     * @param viewUpdate
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean updateView(ViewUpdate viewUpdate, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        ViewWithSource viewWithSource = viewMapper.getViewWithSource(viewUpdate.getId());
        if (null == viewWithSource) {
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getViewPermission() < UserPermissionEnum.WRITE.getPermission()) {
            throw new UnAuthorizedExecption("you have not permission to update this view");
        }

        if (isExist(viewUpdate.getName(), viewUpdate.getId(), viewWithSource.getProjectId())) {
            log.info("the view {} name is already taken", viewUpdate.getName());
            throw new ServerException("the view name is already taken");
        }

        Source source = viewWithSource.getSource();
        if (null == source) {
            log.info("source not found");
            throw new NotFoundException("source is not found");
        }

        //测试连接
        boolean testConnection = sourceService.isTestConnection(new SourceConfig(source));

        if (testConnection) {

            String originStr = viewWithSource.toString();
            BeanUtils.copyProperties(viewUpdate, viewWithSource);
            viewWithSource.updatedBy(user.getId());

            int update = viewMapper.update(viewWithSource);
            if (update > 0) {
                optLogger.info("view ({}) is updated by user(:{}), origin: ({})", viewWithSource.toString(), user.getId(), originStr);
                if (null == viewUpdate.getRoles() || viewUpdate.getRoles().size() == 0) {
                    relRoleViewMapper.deleteByViewId(viewUpdate.getId());
                } else {
                    if (null != viewUpdate.getRoles() && viewUpdate.getRoles().size() > 0 && !StringUtils.isEmpty(viewUpdate.getVariable())) {
                        checkAndInsertRoleParam(viewUpdate.getVariable(), viewUpdate.getRoles(), user, viewWithSource);
                    }
                }

                return true;
            } else {
                throw new ServerException("update view fail");
            }
        } else {
            throw new ServerException("get source connection fail");
        }
    }


    /**
     * 删除View
     *
     * @param id
     * @param user
     * @return
     */
    @Override
    @Transactional
    public boolean deleteView(Long id, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        View view = viewMapper.getById(id);

        if (null == view) {
            log.info("view (:{}) not found", id);
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = null;
        try {
            projectDetail = projectService.getProjectDetail(view.getProjectId(), user, false);
        } catch (NotFoundException e) {
            throw e;
        } catch (UnAuthorizedExecption e) {
            throw new UnAuthorizedExecption("you have not permission to delete this view");
        }

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);
        if (projectPermission.getViewPermission() < UserPermissionEnum.DELETE.getPermission()) {
            throw new UnAuthorizedExecption("you have not permission to delete this view");
        }

        List<Widget> widgets = widgetMapper.getWidgetsByWiew(id);
        if (null != widgets && widgets.size() > 0) {
            throw new ServerException("The current view has been referenced, please delete the reference and then operate");
        }

        int i = viewMapper.deleteById(id);
        if (i > 0) {
            optLogger.info("view ( {} ) delete by user( :{} )", view.toString(), user.getId());
            relRoleViewMapper.deleteByViewId(id);
        }

        return true;
    }


    /**
     * 执行sql
     *
     * @param executeSql
     * @param user
     * @return
     */
    @Override
    public PaginateWithQueryColumns executeSql(ViewExecuteSql executeSql, User user) throws NotFoundException, UnAuthorizedExecption, ServerException {

        Source source = sourceMapper.getById(executeSql.getSourceId());
        if (null == source) {
            throw new NotFoundException("source is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(source.getProjectId(), user, false);

        ProjectPermission projectPermission = projectService.getProjectPermission(projectDetail, user);

        if (projectPermission.getSourcePermission() == UserPermissionEnum.HIDDEN.getPermission()
                || projectPermission.getViewPermission() < UserPermissionEnum.WRITE.getPermission()) {
            throw new UnAuthorizedExecption("you have not permission to execute sql");
        }

        //结构化Sql
        PaginateWithQueryColumns paginateWithQueryColumns = null;
        try {
            SqlEntity sqlEntity = SqlParseUtils.parseSql(executeSql.getSql(), executeSql.getVariables(), sqlTempDelimiter);
            if (null != sqlUtils && null != sqlEntity) {
                if (!StringUtils.isEmpty(sqlEntity.getSql())) {
                    String srcSql = SqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(), sqlEntity.getAuthParams(), sqlTempDelimiter);

                    SqlUtils sqlUtils = this.sqlUtils.init(source.getJdbcUrl(), source.getUsername(), source.getPassword());

                    List<String> executeSqlList = SqlParseUtils.getSqls(srcSql, false);

                    List<String> querySqlList = SqlParseUtils.getSqls(srcSql, true);

                    if (null != executeSqlList && executeSqlList.size() > 0) {
                        executeSqlList.forEach(sql -> sqlUtils.execute(sql));
                    }
                    if (null != querySqlList && querySqlList.size() > 0) {
                        for (String sql : querySqlList) {
                            paginateWithQueryColumns = sqlUtils.syncQuery4Paginate(sql, null, null, null, executeSql.getLimit());
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException(e.getMessage());
        }
        return paginateWithQueryColumns;
    }

    /**
     * 返回view源数据集
     *
     * @param id
     * @param executeParam
     * @param user
     * @return
     */
    @Override
    public Paginate<Map<String, Object>> getData(Long id, ViewExecuteParam executeParam, User user) throws NotFoundException, UnAuthorizedExecption, ServerException, SQLException {
        if (null == executeParam || (CollectionUtils.isEmpty(executeParam.getGroups()) && CollectionUtils.isEmpty(executeParam.getAggregators()))) {
            return null;
        }

        ViewWithSource viewWithSource = viewMapper.getViewWithSource(id);
        if (null == viewWithSource) {
            log.info("view (:{}) not found", id);
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);

        boolean allowGetData = projectService.allowGetData(projectDetail, user);

        if (!allowGetData) {
            throw new UnAuthorizedExecption("you have not permission to get data");
        }

        return getResultDataList(projectDetail, viewWithSource, executeParam, user);
    }


    public void buildQuerySql(List<String> querySqlList, Source source, ViewExecuteParam executeParam, Set<String> excludeColumns) {
        if (null != executeParam) {
            //构造参数， 原有的被传入的替换
            if (null == executeParam.getGroups() || executeParam.getGroups().length < 1) {
                executeParam.setGroups(null);
            }

            if (null == executeParam.getFilters() || executeParam.getFilters().length < 1) {
                executeParam.setFilters(null);
            }
            STGroup stg = new STGroupFile(Constants.SQL_TEMPLATE);
            ST st = stg.getInstanceOf("querySql");
            st.add("nativeQuery", executeParam.isNativeQuery());
            st.add("groups", executeParam.getGroups());

            if (executeParam.isNativeQuery()) {
                st.add("aggregators", executeParam.getAggregators(excludeColumns));
            } else {
                st.add("aggregators", executeParam.getAggregators(source.getJdbcUrl(), excludeColumns));
            }

            st.add("orders", executeParam.getOrders(source.getJdbcUrl()));
            st.add("filters", executeParam.getFilters());
            st.add("keywordPrefix", sqlUtils.getKeywordPrefix(source.getJdbcUrl()));
            st.add("keywordSuffix", sqlUtils.getKeywordSuffix(source.getJdbcUrl()));

            for (int i = 0; i < querySqlList.size(); i++) {
                st.add("sql", querySqlList.get(i));
                querySqlList.set(i, st.render());
            }

        }
    }


    /**
     * 获取结果集
     *
     * @param projectDetail
     * @param viewWithSource
     * @param executeParam
     * @param user
     * @return
     * @throws ServerException
     */
    @Override
    public PaginateWithQueryColumns getResultDataList(ProjectDetail projectDetail, ViewWithSource viewWithSource, ViewExecuteParam executeParam, User user) throws ServerException, SQLException {
        PaginateWithQueryColumns paginate = null;

        if (null == executeParam || (CollectionUtils.isEmpty(executeParam.getGroups()) && CollectionUtils.isEmpty(executeParam.getAggregators()))) {
            return null;
        }

        if (null == viewWithSource.getSource()) {
            throw new NotFoundException("source is not found");
        }

        String cacheKey = null;
        try {

            if (!StringUtils.isEmpty(viewWithSource.getSql())) {
                //解析变量
                List<SqlVariable> variables = viewWithSource.getVariables();
                //解析sql
                SqlEntity sqlEntity = SqlParseUtils.parseSql(viewWithSource.getSql(), variables, sqlTempDelimiter);

                //获取当前用户对该view的行列权限配置
                List<RelRoleView> roleViewList = relRoleViewMapper.getByUserAndView(user.getId(), viewWithSource.getId());

                //行权限
                List<SqlVariable> rowVariables = getRowVariables(roleViewList, variables);

                //列权限（只记录被限制访问的字段）
                Set<String> excludeColumns = getColumnAuth(roleViewList);

                //解析行权限
                parseParams(projectDetail, sqlEntity, executeParam.getParams(), rowVariables, user);

                //替换参数
                String srcSql = SqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(), sqlEntity.getAuthParams(), sqlTempDelimiter);

                Source source = viewWithSource.getSource();

                SqlUtils sqlUtils = this.sqlUtils.init(source);


                List<String> executeSqlList = SqlParseUtils.getSqls(srcSql, false);
                if (null != executeSqlList && executeSqlList.size() > 0) {
                    executeSqlList.forEach(sql -> sqlUtils.execute(sql));
                }

                List<String> querySqlList = SqlParseUtils.getSqls(srcSql, true);
                if (null != querySqlList && querySqlList.size() > 0) {
                    buildQuerySql(querySqlList, source, executeParam, excludeColumns);

                    if (null != executeParam
                            && null != executeParam.getCache()
                            && executeParam.getCache()
                            && executeParam.getExpired() > 0L) {

                        StringBuilder pageInfoStringBuilder = new StringBuilder();
                        pageInfoStringBuilder.append(executeParam.getPageNo());
                        pageInfoStringBuilder.append(minus);
                        pageInfoStringBuilder.append(executeParam.getLimit());
                        pageInfoStringBuilder.append(minus);
                        pageInfoStringBuilder.append(executeParam.getPageSize());

                        cacheKey = MD5Util.getMD5(pageInfoStringBuilder.toString() + querySqlList.get(querySqlList.size() - 1), true, 32);

                        try {
                            Object object = redisUtils.get(cacheKey);
                            if (null != object && executeParam.getCache()) {
                                paginate = (PaginateWithQueryColumns) object;
                                return paginate;
                            }
                        } catch (Exception e) {
                            log.warn("get data by cache: {}", e.getMessage());
                        }
                    }

                    for (String sql : querySqlList) {
                        paginate = sqlUtils.syncQuery4Paginate(
                                sql,
                                executeParam.getPageNo(),
                                executeParam.getPageSize(),
                                executeParam.getTotalCount(),
                                executeParam.getLimit()
                        );
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException(e.getMessage());
        }

        if (null != executeParam
                && null != executeParam.getCache()
                && executeParam.getCache()
                && executeParam.getExpired() > 0L
                && null != paginate && paginate.getResultList().size() > 0) {
            redisUtils.set(cacheKey, paginate, executeParam.getExpired(), TimeUnit.SECONDS);
        }

        return paginate;
    }


    @Override
    public List<Map<String, Object>> getDistinctValue(Long id, DistinctParam param, User user) throws NotFoundException, ServerException, UnAuthorizedExecption {
        ViewWithSource viewWithSource = viewMapper.getViewWithSource(id);
        if (null == viewWithSource) {
            log.info("view (:{}) not found", id);
            throw new NotFoundException("view is not found");
        }

        ProjectDetail projectDetail = projectService.getProjectDetail(viewWithSource.getProjectId(), user, false);

        boolean allowGetData = projectService.allowGetData(projectDetail, user);

        if (!allowGetData) {
            throw new UnAuthorizedExecption();
        }

        return getDistinctValueData(projectDetail, viewWithSource, param, user);
    }


    @Override
    public List<Map<String, Object>> getDistinctValueData(ProjectDetail projectDetail, ViewWithSource viewWithSource, DistinctParam param, User user) throws ServerException {
        try {
            if (!StringUtils.isEmpty(viewWithSource.getSql())) {
                //解析变量
                List<SqlVariable> variables = viewWithSource.getVariables();
                //解析sql
                SqlEntity sqlEntity = SqlParseUtils.parseSql(viewWithSource.getSql(), variables, sqlTempDelimiter);

                //获取当前用户对该view的行列权限配置
                List<RelRoleView> roleViewList = relRoleViewMapper.getByUserAndView(user.getId(), viewWithSource.getId());

                //行权限
                List<SqlVariable> rowVariables = getRowVariables(roleViewList, variables);

                //解析行权限
                parseParams(projectDetail, sqlEntity, null, rowVariables, user);

                //替换参数
                String srcSql = SqlParseUtils.replaceParams(sqlEntity.getSql(), sqlEntity.getQuaryParams(), sqlEntity.getAuthParams(), sqlTempDelimiter);

                Source source = viewWithSource.getSource();

                SqlUtils sqlUtils = this.sqlUtils.init(source);

                List<String> executeSqlList = SqlParseUtils.getSqls(srcSql, false);
                if (null != executeSqlList && executeSqlList.size() > 0) {
                    executeSqlList.forEach(sql -> sqlUtils.execute(sql));
                }

                List<String> querySqlList = SqlParseUtils.getSqls(srcSql, true);
                if (null != querySqlList && querySqlList.size() > 0) {
                    if (null != param) {
                        STGroup stg = new STGroupFile(Constants.SQL_TEMPLATE);
                        ST st = stg.getInstanceOf("queryDistinctSql");
                        st.add("columns", param.getColumns());
                        st.add("params", param.getParents());
                        st.add("sql", querySqlList.get(querySqlList.size() - 1));
                        st.add("keywordPrefix", SqlUtils.getKeywordPrefix(source.getJdbcUrl()));
                        st.add("keywordSuffix", SqlUtils.getKeywordSuffix(source.getJdbcUrl()));

                        querySqlList.set(querySqlList.size() - 1, st.render());
                    }
                    List<Map<String, Object>> list = null;
                    for (String sql : querySqlList) {
                        list = sqlUtils.query4List(sql, -1);
                    }
                    if (null != list) {
                        return list;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServerException(e.getMessage());
        }

        return null;
    }


    private Set<String> getColumnAuth(List<RelRoleView> roleViewList) {
        if (null != roleViewList && roleViewList.size() > 0) {
            Set<String> columns = new HashSet<>();
            roleViewList.forEach(r -> {
                if (!StringUtils.isEmpty(r.getColumnAuth())) {
                    columns.addAll(Arrays.asList(r.getColumnAuth().split(comma)));
                }
            });
            return columns;
        }
        return null;
    }

    private List<SqlVariable> getRowVariables(List<RelRoleView> roleViewList, List<SqlVariable> variables) {
        if (null != roleViewList && roleViewList.size() > 0 && null != variables && variables.size() > 0) {
            List<SqlVariable> list = new ArrayList<>();
            Map<String, SqlVariable> map = new HashMap<>();
            variables.forEach(v -> map.put(v.getName(), v));

            roleViewList.forEach(r -> {
                if (!StringUtils.isEmpty(r.getRowAuth())) {
                    List<AuthParamValue> authParamValues = JSONObject.parseArray(r.getRowAuth(), AuthParamValue.class);
                    authParamValues.forEach(v -> {
                        if (map.containsKey(v.getName())) {
                            SqlVariable sqlVariable = map.get(v.getName());
                            sqlVariable.setDefaultValues(v.getValues());
                            list.add(sqlVariable);
                        }
                    });
                }
            });
            return list;
        }
        return null;
    }


    private void parseParams(ProjectDetail projectDetail, SqlEntity sqlEntity, List<Param> paramList, List<SqlVariable> variables, User user) {
        //查询参数
        if (null != paramList && paramList.size() > 0) {
            if (null == sqlEntity.getQuaryParams()) {
                sqlEntity.setQuaryParams(new HashMap<>());
            }
            paramList.forEach(p -> sqlEntity.getQuaryParams().put(p.getName().trim(), p.getValue()));
        }

        //如果当前用户是project的维护者，直接不走行权限
        if (projectService.isMaintainer(projectDetail, user)) {
            sqlEntity.setAuthParams(null);
            sqlEntity.setQuaryParams(null);
            return;
        }

        //权限参数
        if (null != variables && variables.size() > 0) {
            List<SqlVariable> list = variables.stream().filter(v -> v.getType().equals(SqlVariableTypeEnum.AUTHVARE.getType())).collect(Collectors.toList());
            if (null != list && list.size() > 0) {
                ExecutorService executorService = Executors.newFixedThreadPool(7);
                CountDownLatch countDownLatch = new CountDownLatch(list.size());
                ConcurrentHashMap<String, Set<String>> map = new ConcurrentHashMap<>();
                try {
                    list.forEach(sqlVariable -> executorService.execute(() -> {
                        //TODO 外部获取参数url
                        String url = "";
                        List<String> values = SqlParseUtils.getAuthVarValue(sqlVariable, url);
                        if (map.containsKey(sqlVariable.getName().trim())) {
                            map.get(sqlVariable.getName().trim()).addAll(values);
                        } else {
                            map.put(sqlVariable.getName().trim(), new HashSet<>(values));
                        }
                        countDownLatch.countDown();
                    }));
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    executorService.shutdown();
                }

                if (map.size() > 0) {
                    if (null == sqlEntity.getAuthParams()) {
                        sqlEntity.setAuthParams(new HashMap<>());
                    }
                    map.forEach((k, v) -> sqlEntity.getAuthParams().put(k, new ArrayList<String>(v)));
                }
            } else {
                sqlEntity.setAuthParams(new HashMap<>());
            }
        }
    }

    private Map<String, Object> getQueryParam(SqlEntity sqlEntity, ViewExecuteParam viewExecuteParam) {
        Map<String, Object> map = null;
        if (null != sqlEntity && null != viewExecuteParam) {
            map = new HashMap<>();
            if (null != sqlEntity.getQuaryParams() && sqlEntity.getQuaryParams().size() > 0) {
                map.putAll(sqlEntity.getQuaryParams());
            }
            if (null != viewExecuteParam.getParams() && viewExecuteParam.getParams().size() > 0) {
                for (Param param : viewExecuteParam.getParams()) {
                    map.put(param.getName().trim(), param.getValue());
                }
            }
        }
        return map;
    }


    private void checkAndInsertRoleParam(String sqlVarible, List<RelRoleViewDto> roles, User user, View view) {
        List<SqlVariable> variables = JSONObject.parseArray(sqlVarible, SqlVariable.class);
        if (null != variables && variables.size() > 0) {
            new Thread(() -> {
                Set<String> vars = variables.stream().map(SqlVariable::getName).collect(Collectors.toSet());
                List<RelRoleView> relRoleViews = new ArrayList<>();
                roles.forEach(r -> {
                    if (!StringUtils.isEmpty(r.getRowAuth()) && r.getRoleId().longValue() > 0L) {
                        JSONArray jsonArray = JSONObject.parseArray(r.getRowAuth());
                        if (null != jsonArray && jsonArray.size() > 0) {
                            for (int i = 0; i < jsonArray.size(); i++) {
                                JSONObject jsonObject = jsonArray.getJSONObject(i);
                                String name = jsonObject.getString(SQL_VARABLE_KEY);
                                if (vars.contains(name)) {
                                    RelRoleView relRoleView = new RelRoleView().createdBy(user.getId());
                                    BeanUtils.copyProperties(r, relRoleView);
                                    relRoleView.setViewId(view.getId());
                                    relRoleViews.add(relRoleView);
                                }
                            }
                        }
                    }
                });
                relRoleViewMapper.insertBatch(relRoleViews);
            }).start();
        }
    }

}

