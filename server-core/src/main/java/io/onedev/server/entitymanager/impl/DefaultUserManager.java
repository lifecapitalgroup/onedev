package io.onedev.server.entitymanager.impl;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.hibernate.ReplicationMode;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;

import com.google.common.base.Preconditions;
import com.hazelcast.core.HazelcastInstance;

import io.onedev.server.SubscriptionManager;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.AccessTokenManager;
import io.onedev.server.entitymanager.EmailAddressManager;
import io.onedev.server.entitymanager.IssueFieldManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.Listen;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.entity.EntityRemoved;
import io.onedev.server.event.system.SystemStarting;
import io.onedev.server.exception.NoSubscriptionException;
import io.onedev.server.model.AbstractEntity;
import io.onedev.server.model.EmailAddress;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.model.support.code.BranchProtection;
import io.onedev.server.model.support.code.TagProtection;
import io.onedev.server.persistence.IdManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.BaseEntityManager;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.persistence.dao.EntityCriteria;
import io.onedev.server.util.facade.UserCache;
import io.onedev.server.util.facade.UserFacade;
import io.onedev.server.util.usage.Usage;

@Singleton
public class DefaultUserManager extends BaseEntityManager<User> implements UserManager {
	
    private final ProjectManager projectManager;
    
    private final SettingManager settingManager;
    
    private final IssueFieldManager issueFieldManager;
    
    private final IdManager idManager;
    
    private final EmailAddressManager emailAddressManager;
            
    private final TransactionManager transactionManager;
    
    private final ClusterManager clusterManager;

	private final SubscriptionManager subscriptionManager;
	
	private volatile UserCache cache;
	
	@Inject
    public DefaultUserManager(Dao dao, ProjectManager projectManager, SettingManager settingManager, 
							  IssueFieldManager issueFieldManager, IdManager idManager, 
							  EmailAddressManager emailAddressManager, AccessTokenManager accessTokenManager,
							  TransactionManager transactionManager, ClusterManager clusterManager, 
							  SubscriptionManager subscriptionManager) {
        super(dao);
        
        this.projectManager = projectManager;
        this.settingManager = settingManager;
        this.issueFieldManager = issueFieldManager;
        this.idManager = idManager;
        this.emailAddressManager = emailAddressManager;
        this.transactionManager = transactionManager;
        this.clusterManager = clusterManager;
		this.subscriptionManager = subscriptionManager;
    }

	@Transactional
	@Override
	public void replicate(User user) {
		getSession().replicate(user, ReplicationMode.OVERWRITE);
		idManager.useId(User.class, user.getId());
		var facade = user.getFacade();
		if (facade.getId() > 0)
			transactionManager.runAfterCommit(() -> cache.put(facade.getId(), facade));
	}
	
    @Transactional
    @Override
	public void update(User user, String oldName) {
		Preconditions.checkState(!user.isNew());
		
    	user.setName(user.getName().toLowerCase());
    	
    	dao.persist(user);

    	if (oldName != null && !oldName.equals(user.getName())) {
    		for (Project project: projectManager.query()) {
				try {
					for (BranchProtection protection : project.getBranchProtections())
						protection.onRenameUser(oldName, user.getName());
					for (TagProtection protection : project.getTagProtections())
						protection.onRenameUser(oldName, user.getName());
					project.getIssueSetting().onRenameUser(oldName, user.getName());
					project.getBuildSetting().onRenameUser(oldName, user.getName());
					project.getPullRequestSetting().onRenameUser(oldName, user.getName());
				} catch (Exception e) {
					throw new RuntimeException("Error checking user reference in project '" + project.getPath() + "'", e);
				}
    		}
    		settingManager.onRenameUser(oldName, user.getName());
    		issueFieldManager.onRenameUser(oldName, user.getName());
    	}
    }
    
	@Transactional
    @Override
    public void create(User user) {
		Preconditions.checkState(user.isNew());
		if (user.isServiceAccount() && !subscriptionManager.isSubscriptionActive())
			throw new NoSubscriptionException("Service account");
		user.setName(user.getName().toLowerCase());
		dao.persist(user);
    }
    
    @Sessional
    @Override
    public User getRoot() {
    	return load(User.ROOT_ID);
    }

    @Sessional
    @Override
    public User getSystem() {
    	return load(User.SYSTEM_ID);
    }
    
    @Sessional
    @Override
    public User getUnknown() {
    	return load(User.UNKNOWN_ID);
    }
    
    @Transactional
    @Override
	public void delete(User user) {
		checkUsage(user);
    	
    	Query<?> query = getSession().createQuery("update PullRequest set submitter=:unknown where submitter=:submitter");
    	query.setParameter("submitter", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();

		query = getSession().createQuery("update Pack set user=:unknown where user=:user");
		query.setParameter("user", user);
		query.setParameter("unknown", getUnknown());
		query.executeUpdate();
		
    	query = getSession().createQuery("update Build set submitter=:unknown where submitter=:submitter");
    	query.setParameter("submitter", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update Build set canceller=:unknown where canceller=:canceller");
    	query.setParameter("canceller", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequest set lastActivity.user=:unknown where lastActivity.user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequestChange set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update PullRequestComment set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update CodeComment set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update CodeComment set lastActivity.user=:unknown where lastActivity.user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update CodeCommentReply set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update CodeCommentStatusChange set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update Issue set submitter=:unknown where submitter=:submitter");
    	query.setParameter("submitter", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update Issue set lastActivity.user=:unknown where lastActivity.user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update IssueComment set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
    	
    	query = getSession().createQuery("update IssueChange set user=:unknown where user=:user");
    	query.setParameter("user", user);
    	query.setParameter("unknown", getUnknown());
    	query.executeUpdate();
		
		dao.remove(user);
    }

	private void checkUsage(User user) {
    	Usage usage = new Usage();
		for (Project project: projectManager.query()) {
			try {
				Usage usageInProject = new Usage();
				for (BranchProtection protection : project.getBranchProtections())
					usageInProject.add(protection.onDeleteUser(user.getName()));
				for (TagProtection protection : project.getTagProtections())
					usageInProject.add(protection.onDeleteUser(user.getName()));
				usageInProject.add(project.getIssueSetting().onDeleteUser(user.getName()));
				usageInProject.add(project.getBuildSetting().onDeleteUser(user.getName()));
				usageInProject.add(project.getPullRequestSetting().onDeleteUser(user.getName()));
				usageInProject.prefix("project '" + project.getPath() + "': settings");
				usage.add(usageInProject);
			} catch (Exception e) {
				throw new RuntimeException("Error checking user reference in project '" + project.getPath() + "'", e);
			}
		}

		usage.add(settingManager.onDeleteUser(user.getName()));
		
		usage.checkInUse("User '" + user.getName() + "'");    	
	}

	@Transactional
	@Override
	public void disable(User user) {
		checkUsage(user);
		
		for (var authorization: user.getProjectAuthorizations())
			dao.remove(authorization);
		for (var membership: user.getMemberships())
			dao.remove(membership);
		for (var authorization: user.getIssueAuthorizations())
			dao.remove(authorization);
		for (var dashboard: user.getDashboards())
			dao.remove(dashboard);
		for (var token: user.getAccessTokens())
			dao.remove(token);
		for (var visit: user.getDashboardVisits())
			dao.remove(visit);
		for (var share: user.getDashboardShares())
			dao.remove(share);	
		for (var review: user.getPullRequestReviews())
			dao.remove(review);
		for (var assignment: user.getPullRequestAssignments())
			dao.remove(assignment);
		for (var watch: user.getPullRequestWatches())
			dao.remove(watch);
		for (var watch: user.getIssueWatches())
			dao.remove(watch);
		for (var vote: user.getIssueVotes())
			dao.remove(vote);
		for (var stopwatch: user.getStopwatches())
			dao.remove(stopwatch);

		for (var personalization: user.getIssueQueryPersonalizations())
			dao.remove(personalization);
		for (var personalization: user.getBuildQueryPersonalizations())
			dao.remove(personalization);
		for (var personalization: user.getPackQueryPersonalizations())
			dao.remove(personalization);
		for (var personalization: user.getPullRequestQueryPersonalizations())
			dao.remove(personalization);
		for (var personalization: user.getCommitQueryPersonalizations())
			dao.remove(personalization);
		for (var personalization: user.getCodeCommentQueryPersonalizations())
			dao.remove(personalization);

		for (var sshKey: user.getSshKeys())
			dao.remove(sshKey);
		for (var gpgKey: user.getGpgKeys())
			dao.remove(gpgKey);
		for (var pendingSuggestionApply: user.getPendingSuggestionApplies())
			dao.remove(pendingSuggestionApply);
		for (var mention: user.getCodeCommentMentions())
			dao.remove(mention);
		for (var mention: user.getIssueMentions())
			dao.remove(mention);
		for (var mention: user.getPullRequestMentions())
			dao.remove(mention);

		user.setPassword(null);
		user.setPasswordResetCode(null);
		user.setDisabled(true);

		dao.persist(user);
	}
	
	@Transactional
	@Override
	public void delete(Collection<User> users) {
		for (var user: users)
			delete(user);
	}

	@Transactional
	@Override
	public void disable(Collection<User> users) {
		for (var user: users) 
			disable(user);
	}

	@Transactional
	@Override
	public void enable(User user) {
		user.setDisabled(false);
		dao.persist(user);
	}

	@Transactional
	@Override
	public void enable(Collection<User> users) {
		for (var user: users)
			enable(user);
	}
	
	@Sessional
    @Override
    public User findByName(String userName) {
		UserFacade facade = cache.findByName(userName);
		if (facade != null)
			return load(facade.getId());
		else
			return null;
    }

    @Override
    public UserFacade findFacadeById(Long userId) {
		return cache.get(userId);
    }
	
	@Sessional
    @Override
    public User findByFullName(String fullName) {
		UserFacade facade = cache.findByFullName(fullName);
		if (facade != null)
			return load(facade.getId());
		else
			return null;
    }

	@Sessional
	@Override
	public User findByPasswordResetCode(String passwordResetCode) {
		var criteria = newCriteria();
		criteria.add(Restrictions.eq("passwordResetCode", passwordResetCode));
		return find(criteria);
	}
	
	@Override
	public List<User> query() {
		EntityCriteria<User> criteria = newCriteria();
		criteria.add(Restrictions.gt("id", 0L));
		criteria.setCacheable(true);
		return query(criteria);
	}
	
	@Override
	public int count() {
		return cache.size();
	}

    @Sessional
    @Listen
    public void on(SystemStarting event) {
		HazelcastInstance hazelcastInstance = clusterManager.getHazelcastInstance();
		
		// Use replicated map, otherwise it will be slow to display many user avatars
		// (in issue list for instance) which will call findFacadeById many times
        cache = new UserCache(hazelcastInstance.getReplicatedMap("userCache"));
		for (User user: query())
			cache.put(user.getId(), user.getFacade());
    }

	private Predicate[] getPredicates(CriteriaBuilder builder, CriteriaQuery<?> query, 
			Root<User> root, String term) {
		if (term != null) {
			term = "%" + term.toLowerCase() + "%";
			
			Subquery<EmailAddress> addressQuery = query.subquery(EmailAddress.class);
			Root<EmailAddress> addressRoot = addressQuery.from(EmailAddress.class);
			addressQuery.select(addressRoot);
			
			Predicate ownerPredicate = builder.equal(addressRoot.get(EmailAddress.PROP_OWNER), root);
			Predicate valuePredicate = builder.like(addressRoot.get(EmailAddress.PROP_VALUE), term);
			return new Predicate[] {
					builder.gt(root.get(AbstractEntity.PROP_ID), 0),
					builder.or(
							builder.like(root.get(User.PROP_NAME), term), 
							builder.like(builder.lower(root.get(User.PROP_FULL_NAME)), term), 
							builder.exists(addressQuery.where(ownerPredicate, valuePredicate)))};
		} else {
			return new Predicate[] {builder.gt(root.get(AbstractEntity.PROP_ID), 0)};
		}
	}
	
	@Override
	public List<User> query(String term, int firstResult, int maxResults) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<User> criteriaQuery = builder.createQuery(User.class);
		Root<User> root = criteriaQuery.from(User.class);
		
		criteriaQuery.where(getPredicates(builder, criteriaQuery, root, term));
		criteriaQuery.orderBy(builder.asc(root.get(User.PROP_NAME)));
		
		Query<User> query = getSession().createQuery(criteriaQuery);
		query.setFirstResult(firstResult);
		query.setMaxResults(maxResults);
		
		return query.getResultList();
	}

	@Sessional
	@Override
	public int count(String term) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
		Root<User> root = criteriaQuery.from(User.class);
		
		criteriaQuery.select(builder.count(root));
		criteriaQuery.where(getPredicates(builder, criteriaQuery, root, term));

		return getSession().createQuery(criteriaQuery).uniqueResult().intValue();
	}

	@Sessional
	@Override
	public User findByVerifiedEmailAddress(String emailAddressValue) {
		EmailAddress emailAddress = emailAddressManager.findByValue(emailAddressValue);
		if (emailAddress != null && emailAddress.isVerified()) 
			return emailAddress.getOwner();
		else
			return null;
	}

	@Override
	public UserCache cloneCache() {
		return cache.clone();
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		// Cache will be null when we run reset-admin-password command
		if (cache != null && event.getEntity() instanceof User) {
			var facade = (UserFacade) event.getEntity().getFacade();
			if (facade.getId() > 0)
				transactionManager.runAfterCommit(() -> cache.put(facade.getId(), facade));
		}
	}

	@Transactional
	@Listen
	public void on(EntityRemoved event) {
		// Cache will be null when we run reset-admin-password command
		if (cache != null && event.getEntity() instanceof User) {
			var id = event.getEntity().getId();
			transactionManager.runAfterCommit(() -> cache.remove(id));
		}
	}
	
}